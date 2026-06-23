import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Núcleo da aplicação: implementa o nó (máquina) do anel.
 *
 * Threads:
 *   - receiver : bloqueia em socket.receive() e trata cada pacote recebido.
 *   - monitor  : controla o token (timeout / token perdido) e recupera dados
 *                presos. Apenas a máquina mestre regenera o token perdido.
 *   - bootstrap: tenta gerar o primeiro token periodicamente (mecanismo de
 *                reserva). Na prática a geração é disparada de forma reativa,
 *                assim que a máquina mestre descobre a segunda máquina do
 *                anel (ver tryStartFirstToken), o que evita o caso degenerado
 *                de um anel com 1 só máquina enviar o token "para si mesma" e
 *                disparar uma falsa detecção de token duplicado.
 *   - menu     : (em Main) lê o teclado e interage com este nó.
 *
 * Todo o processamento de pacotes acontece na thread receiver, o que serializa
 * naturalmente as transições de estado do token. As flags compartilhadas com as
 * demais threads são 'volatile'; os envios pelo socket são protegidos por um
 * lock para não intercalar bytes.
 */
public class RingNode {
    private final Config cfg;
    private final DatagramSocket socket;
    private final int bindPort;
    private final List<InetSocketAddress> discoveryTargets;
    private final PeerRegistry peers;
    private final MessageQueue queue = new MessageQueue();
    private final Random rng = new Random();
    private final String selfNick;
    private final String selfIp;
    private final long selfBirthTime; // instante (epoch ms) em que esta máquina entrou na rede
    private final Object sendLock = new Object();

    // ===== Estado do token / dados (compartilhado entre threads) =====
    private volatile boolean removeTokenRequested = false; // usuário pediu retirada do token
    private volatile boolean awaitingReturn = false;       // origem aguardando os dados darem a volta
    private volatile long dataSentAt = 0L;                  // instante do último envio de dados
    private volatile long lastRingActivity = System.currentTimeMillis(); // último token/dado visto
    private volatile long lastTokenAtMaster = 0L;          // última passagem do token pelo mestre
    private volatile boolean firstTokenDone = false;       // já existe token na rede?
    private volatile boolean running = true;
    // true apenas durante o sleepSeconds(tokenTime) em onToken() — usado por leave()
    // para acordar o receiver e garantir que o token seja repassado antes de sair.
    private volatile boolean inTokenSleep = false;
    private Thread receiverThread;

    public RingNode(Config cfg, int bindPort, List<InetSocketAddress> discoveryTargets) throws Exception {
        this.cfg = cfg;
        this.selfNick = cfg.nickname;
        this.bindPort = bindPort;
        this.discoveryTargets = discoveryTargets;
        this.selfIp = primaryLocalIp();
        this.selfBirthTime = System.currentTimeMillis();

        // Socket UDP ligado à porta local, com reuso de endereço e broadcast.
        this.socket = new DatagramSocket(null);
        this.socket.setReuseAddress(true);
        this.socket.setBroadcast(true);
        this.socket.bind(new InetSocketAddress(bindPort));

        this.peers = new PeerRegistry(selfNick, InetAddress.getByName(selfIp), bindPort, selfBirthTime);
    }

    public void start() {
        log("Máquina '" + selfNick + "' iniciada em " + selfIp + ":" + bindPort + " | " + cfg);
        receiverThread = startDaemon(this::receiveLoop, "receiver");
        startDaemon(this::monitorLoop, "monitor");
        startDaemon(this::bootstrapToken, "bootstrap");
        sendDiscover();
    }

    private Thread startDaemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ===== DESCOBERTA (DISCOVER/HELLO) =====

    /** Envia DISCOVER (broadcast) se identificando (com o carimbo de entrada). */
    public void sendDiscover() {
        broadcast(Packet.discover(selfNick, selfIp, bindPort, selfBirthTime));
        log("DISCOVER enviado (procurando outras máquinas).");
    }

    private void sendHello() {
        broadcast(Packet.hello(selfNick, selfIp, bindPort, selfBirthTime));
    }

    private void broadcast(String msg) {
        if (discoveryTargets.isEmpty()) {
            log("[BROADCAST] nenhum target de descoberta configurado!");
        }
        for (InetSocketAddress t : discoveryTargets) {
            sendRaw(t.getAddress(), t.getPort(), msg);
        }
    }

    // ===== RECEPÇÃO =====

    private void receiveLoop() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                String raw = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                // Remove apenas quebras de linha ao final (artefato de transporte),
                // preservando o conteúdo da mensagem para o cálculo do CRC.
                while (raw.endsWith("\n") || raw.endsWith("\r")) {
                    raw = raw.substring(0, raw.length() - 1);
                }
                handle(raw, pkt.getAddress(), pkt.getPort());
            } catch (Exception e) {
                if (running) log("Erro na recepção: " + e.getMessage());
            }
        }
    }

    private void handle(String raw, InetAddress src, int srcPort) {
        if (raw.isEmpty()) return;
        switch (Packet.typeOf(raw)) {
            case Packet.DISCOVER: onDiscover(raw, src, srcPort); break;
            case Packet.HELLO:    onHello(raw, src, srcPort);    break;
            case Packet.LEAVE:    onLeave(raw);                  break;
            case Packet.TOKEN:    onToken();                     break;
            case Packet.DATA:     onData(raw);                   break;
            default:              log("Pacote desconhecido recebido: " + raw);
        }
    }

    private void onDiscover(String raw, InetAddress src, int srcPort) {
        String[] p = raw.split(":", 5);
        if (p.length < 2) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return;

        // O DISCOVER agora inclui a porta: "10:nick:ip:porta[:birthTime]"
        // Se não tiver porta (compatibilidade com versões antigas), usa a porta padrão
        int discoveredPort = bindPort; // padrão
        if (p.length >= 4) {
            try {
                discoveredPort = Integer.parseInt(p[3].trim());
            } catch (NumberFormatException ignored) {
            }
        }

        // Usa o IP fornecido no DISCOVER (que pode ser localhost ou IP da LAN)
        InetAddress discoveredAddr = src; // usa o IP de origem como fallback
        if (p.length >= 3 && !p[2].trim().isEmpty()) {
            try {
                // Tenta usar o IP declarado no DISCOVER
                discoveredAddr = InetAddress.getByName(p[2].trim());
            } catch (Exception ignored) {
                // Se falhar, usa o IP de origem
            }
        }

        boolean changed = peers.addOrUpdate(nick, discoveredAddr, discoveredPort, parseBirth(p));
        if (changed) {
            log("DISCOVER de '" + nick + "' (" + src.getHostAddress() + ":" + srcPort
                    + "). Nova topologia: " + peers.diagram());
            tryStartFirstToken("nova máquina descoberta: '" + nick + "'");
        }
        sendHello(); // responde se identificando (em broadcast)
    }

    private void onHello(String raw, InetAddress src, int srcPort) {
        String[] p = raw.split(":", 5);
        if (p.length < 2) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return;

        // O HELLO agora inclui a porta: "20:nick:ip:porta[:birthTime]"
        int discoveredPort = bindPort; // padrão
        if (p.length >= 4) {
            try {
                discoveredPort = Integer.parseInt(p[3].trim());
            } catch (NumberFormatException ignored) {
            }
        }

        InetAddress discoveredAddr = src;
        if (p.length >= 3 && !p[2].trim().isEmpty()) {
            try {
                discoveredAddr = InetAddress.getByName(p[2].trim());
            } catch (Exception ignored) {
            }
        }

        boolean changed = peers.addOrUpdate(nick, discoveredAddr, discoveredPort, parseBirth(p));
        if (changed) {
            log("HELLO de '" + nick + "' (" + src.getHostAddress() + ":" + srcPort
                    + "). Nova topologia: " + peers.diagram());
            tryStartFirstToken("nova máquina descoberta: '" + nick + "'");
        }
    }

    private void onLeave(String raw) {
        String[] p = raw.split(":", 3);
        if (p.length < 2) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return; // ignora eco do próprio broadcast
        boolean removed = peers.remove(nick);
        if (!removed) return;
        log("LEAVE de '" + nick + "': saiu da rede graciosamente. Nova topologia: " + peers.diagram());

        // Se estávamos aguardando retorno de dados cujo destino era o nó que saiu,
        // não há mais ninguém para confirmar (ACK/NAK). Libera o token imediatamente.
        if (awaitingReturn) {
            OutgoingMessage m = queue.peek();
            if (m != null && m.dest.equals(nick)) {
                log("[LEAVE] destino '" + nick + "' saiu. Descartando mensagem pendente e liberando token.");
                queue.removeHead();
                awaitingReturn = false;
                dataSentAt = 0;
                forwardToken();
            }
        }
    }

    /**
     * Extrai o carimbo de entrada (epoch ms) de um DISCOVER/HELLO já dividido.
     * Pacotes sem o campo (formato antigo "tipo:apelido:ip") são aceitos: assume-se
     * o instante atual como entrada, o que não permite que essa máquina seja eleita
     * mestre à frente de quem realmente anunciou um carimbo mais antigo.
     */
    private static long parseBirth(String[] p) {
        if (p.length >= 4) {
            try {
                return Long.parseLong(p[3].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return System.currentTimeMillis();
    }

    // ===== TOKEN =====

    private void onToken() {
        lastRingActivity = System.currentTimeMillis();

        // Controle do token feito pela máquina mestre: detecta DOIS tokens.
        // Só faz sentido com pelo menos 2 máquinas no anel; com 1 só máquina
        // (ainda sozinha), não há "duplicidade" real a detectar aqui (esse
        // caso degenerado é evitado em forwardToken(), que não envia o token
        // para si mesma).
        if (peers.isMaster() && peers.size() > 1) {
            long now = System.currentTimeMillis();
            long minMs = (long) (cfg.minTokenInterval * 1000);
            if (lastTokenAtMaster > 0 && (now - lastTokenAtMaster) < minMs) {
                log("[MONITOR] token voltou em " + (now - lastTokenAtMaster) + "ms (< mínimo " + minMs
                        + "ms): HÁ MAIS DE UM TOKEN na rede. Removendo este token.");
                lastTokenAtMaster = now;
                return; // consome (remove) o token duplicado
            }
            lastTokenAtMaster = now;
        }

        // Se ainda aguardo o retorno dos meus dados, eu já detenho o token:
        // um token que chegue agora é duplicado.
        if (awaitingReturn) {
            log("[TOKEN] recebido enquanto aguardo o retorno dos dados: token duplicado, descartado.");
            return;
        }

        // Retirada do token solicitada pelo usuário.
        if (removeTokenRequested) {
            removeTokenRequested = false;
            log("[TOKEN] retirado da rede a pedido do usuário.");
            return;
        }

        log("[TOKEN] recebido. " + (queue.isEmpty() ? "Fila vazia." : "Há mensagens na fila."));

        // Processa o token imediatamente, sem bloquear a receiver thread.
        // O sleep de visualização acontece após o repasse/envio, não antes.
        OutgoingMessage m = queue.peek();
        if (m != null) {
            inTokenSleep = true;
            sleepSeconds(cfg.tokenTime);
            inTokenSleep = false;
            sendData(m); // detém o token até os dados retornarem à origem
        } else {
            inTokenSleep = true;
            sleepSeconds(cfg.tokenTime);
            inTokenSleep = false;
            forwardToken();
        }
    }

    /** Repassa o token para o sucessor do anel. */
    private void forwardToken() {
        awaitingReturn = false;
        Peer s = peers.successor();
        if (s == null) {
            log("Sem sucessor; token retido.");
            return;
        }
        if (s.nickname.equals(selfNick)) {
            // Anel com 1 só máquina (ainda sozinha): enviar o token "para si
            // mesma" faria o pacote voltar quase instantaneamente e seria
            // erroneamente interpretado como "token duplicado" (ver onToken).
            // Em vez disso, aguardamos: tryStartFirstToken() é chamado de novo
            // tão logo outra máquina seja descoberta (ou periodicamente pelo
            // bootstrap), retomando a circulação normalmente.
            firstTokenDone = false;
            log("[TOKEN] sozinho no anel; aguardando outra máquina entrar para iniciar a circulação.");
            return;
        }
        log("[TOKEN] repassado para '" + s.nickname + "'.");
        sendTo(s, Packet.token());
    }

    /**
     * Gera o primeiro token do anel, caso este nó seja a máquina mestre
     * (a primeira a entrar na rede) e ainda não exista token na rede.
     *
     * Só efetivamente inicia a circulação quando há pelo menos outra máquina
     * conhecida (caso contrário, fica esperando). É chamado de forma reativa
     * a cada nova máquina descoberta (onDiscover/onHello) e, como reserva,
     * periodicamente pela thread de bootstrap — útil caso o HELLO/DISCOVER
     * que dispararia a chamada reativa se perca (UDP não garante entrega).
     *
     * 'synchronized' evita que a thread de bootstrap e a thread receiver
     * gerem o primeiro token simultaneamente (condição de corrida que
     * produziria dois tokens reais na rede).
     */
    private synchronized void tryStartFirstToken(String motivo) {
        if (firstTokenDone) return;
        if (!peers.isMaster()) {
            firstTokenDone = true; // outra máquina (a mestre) vai gerar o token
            return;
        }
        if (peers.size() <= 1) return; // ainda sozinho no anel; aguarda
        firstTokenDone = true;
        lastRingActivity = System.currentTimeMillis();
        lastTokenAtMaster = System.currentTimeMillis();
        log("[MONITOR] sou a máquina inicial do anel ('" + selfNick + "'). Gerando o primeiro token (" + motivo + ").");
        forwardToken();
    }

    /** Opção de menu: gerar/inserir um token na rede (qualquer máquina). */
    public void insertToken() {
        log("[TOKEN] inserção solicitada pelo usuário.");
        firstTokenDone = true;
        lastRingActivity = System.currentTimeMillis();
        lastTokenAtMaster = System.currentTimeMillis();
        forwardToken();
    }

    /** Opção de menu: retirar o token da rede (o próximo a chegar é removido). */
    public void requestRemoveToken() {
        removeTokenRequested = true;
        log("Retirada do token solicitada: o próximo token que chegar será removido.");
    }

    /**
     * Desligamento gracioso: anuncia a saída via broadcast LEAVE para que os
     * demais nós removam este peer do anel e encerra o socket.
     * Deve ser chamado antes de System.exit() pelo menu.
     *
     * Se este nó está no meio do sleep de retenção do token (inTokenSleep),
     * interrompe o sleep para que o receiver repasse o token imediatamente,
     * evitando que o anel perca o token ao sair.
     */
    public void leave() {
        running = false;
        if (inTokenSleep && receiverThread != null) {
            receiverThread.interrupt(); // acorda do sleepSeconds em onToken()
            // aguarda o repasse (no máximo tokenTime + 500 ms)
            long deadline = System.currentTimeMillis() + (long)(cfg.tokenTime * 1000) + 500;
            while (inTokenSleep && System.currentTimeMillis() < deadline) {
                sleepMillis(30);
            }
        }
        log("Saindo da rede (desconexão graciosa). Anunciando LEAVE.");
        broadcast(Packet.leave(selfNick, selfIp));
        socket.close();
    }

    // ===== DADOS =====

    /** Monta e envia o pacote de dados da mensagem do topo da fila. */
    private void sendData(OutgoingMessage m) {
        awaitingReturn = true;
        dataSentAt = System.currentTimeMillis();

        long crc = Packet.crc32(m.original); // controle calculado sobre a mensagem correta
        boolean broadcast = m.dest.equals(Packet.BROADCAST);

        // Módulo de inserção de falhas: corrompe o conteúdo com a probabilidade
        // configurada, mantendo o CRC original -> o destino detecta o erro (NAK).
        // Em broadcast o pacote permanece "maquinainexistente" e não é corrompido.
        boolean inject = !m.forceNoError && !broadcast && (rng.nextInt(100) < cfg.errorProbability);
        String content = inject ? corrupt(m.original) : m.original;

        if (inject) log("[FALHA] erro inserido propositalmente na mensagem para '" + m.dest + "'.");
        if (m.forceNoError) log("[RETRANSMISSÃO] reenviando mensagem para '" + m.dest + "' sem erro.");

        String pkt = Packet.data(selfNick, m.dest, Packet.NONEXISTENT, crc, content);
        Peer s = peers.successor();
        if (s == null) {
            log("Sem sucessor; não há para onde enviar os dados.");
            awaitingReturn = false;
            return;
        }
        log("[DADOS] origem '" + selfNick + "' -> destino '" + m.dest + "'. Enviando para '" + s.nickname
                + "': '" + content + "' (CRC=" + crc + ", controle=" + Packet.NONEXISTENT + ").");
        sendTo(s, pkt);
    }

    private void onData(String raw) {
        lastRingActivity = System.currentTimeMillis();
        DataPacket d = DataPacket.parse(raw);
        if (d == null) {
            log("[DADOS] pacote malformado: " + raw);
            return;
        }

        // 1) O pacote retornou à origem (deu a volta completa no anel).
        if (d.origem.equals(selfNick)) {
            onDataReturn(d);
            return;
        }

        // 2) Broadcast: todas as máquinas leem; o controle permanece "maquinainexistente".
        if (d.destino.equals(Packet.BROADCAST)) {
            log("[BROADCAST] mensagem de '" + d.origem + "': '" + d.message + "'.");
            sleepSeconds(cfg.tokenTime);
            forwardData(d.build());
            return;
        }

        // 3) Sou o destino: recalculo o CRC, imprimo, marco ACK/NAK e repasso.
        if (d.destino.equals(selfNick)) {
            long calc = Packet.crc32(d.message);
            boolean ok = (calc == d.crc);
            d.controle = ok ? Packet.ACK : Packet.NAK;
            log("[DADOS] SOU O DESTINO. Mensagem de '" + d.origem + "': '" + d.message + "'. "
                    + "CRC recebido=" + d.crc + " calculado=" + calc + " -> " + d.controle + ".");
            sleepSeconds(cfg.tokenTime);
            forwardData(d.build());
            return;
        }

        // 4) Não é para mim: apenas repasso para o sucessor.
        log("[DADOS] de '" + d.origem + "' para '" + d.destino + "' (não é para mim). Repassando.");
        sleepSeconds(cfg.tokenTime);
        forwardData(d.build());
    }

    /** Tratamento do pacote de dados quando ele retorna à máquina de origem. */
    private void onDataReturn(DataPacket d) {
        OutgoingMessage m = queue.peek();

        if (d.destino.equals(Packet.BROADCAST)) {
            log("[ORIGEM] broadcast '" + d.message + "' completou a volta no anel (entregue a todos). Removendo da fila.");
            queue.removeHead();
            forwardToken();
            return;
        }

        switch (d.controle) {
            case Packet.ACK:
                log("[ORIGEM] retorno ACK de '" + d.destino + "': entregue com sucesso. Removendo da fila.");
                queue.removeHead();
                forwardToken();
                break;

            case Packet.NONEXISTENT:
                log("[ORIGEM] retorno 'maquinainexistente': destino '" + d.destino
                        + "' não está na rede. Removendo da fila.");
                queue.removeHead();
                forwardToken();
                break;

            case Packet.NAK:
                if (m != null && !m.retransmitScheduled) {
                    log("[ORIGEM] retorno NAK de '" + d.destino + "': erro detectado. A mensagem será "
                            + "RETRANSMITIDA (sem erro) na próxima passagem do token.");
                    m.forceNoError = true;        // reenvia o original, sem inserir erro
                    m.retransmitScheduled = true; // apenas uma retransmissão
                    forwardToken();               // não remove da fila; reenvia quando o token voltar
                } else {
                    log("[ORIGEM] retorno NAK novamente após a retransmissão única. Descartando a mensagem.");
                    queue.removeHead();
                    forwardToken();
                }
                break;

            default:
                log("[ORIGEM] retorno desconhecido '" + d.controle + "'. Liberando o token.");
                queue.removeHead();
                forwardToken();
        }
    }

    private void forwardData(String raw) {
        Peer s = peers.successor();
        if (s == null) {
            log("Sem sucessor; dados descartados.");
            return;
        }
        log("[DADOS] repassado para '" + s.nickname + "'.");
        sendTo(s, raw);
    }

    // ===== MONITOR DO TOKEN =====

    private void monitorLoop() {
        while (running) {
            sleepMillis(200);
            long now = System.currentTimeMillis();

            // Recuperação de dados presos (qualquer origem): se os dados não
            // retornarem em tempo, libera o token para não travar o anel.
            if (awaitingReturn && dataSentAt > 0
                    && now - dataSentAt > (long) (cfg.tokenTimeout * 1000) * 3) {
                log("[MONITOR] os dados não retornaram (possível perda). Liberando o token; "
                        + "a mensagem permanece na fila.");
                awaitingReturn = false;
                dataSentAt = 0;
                forwardToken();
                continue;
            }

            // Controle de token perdido: somente a máquina mestre regenera.
            if (!peers.isMaster()) continue;
            if (!firstTokenDone) continue;
            if (awaitingReturn) continue; // estou legitimamente segurando o token

            long idle = now - lastRingActivity;
            if (idle > (long) (cfg.tokenTimeout * 1000)) {
                log("[MONITOR] TOKEN PERDIDO: sem atividade no anel há " + idle + "ms (> timeout "
                        + (long) (cfg.tokenTimeout * 1000) + "ms). Gerando um novo token.");
                lastRingActivity = now;
                lastTokenAtMaster = now;
                forwardToken();
            }
        }
    }

    /**
     * Mecanismo de reserva: reenvia DISCOVER periodicamente até descobrir outros
     * nós, depois tenta gerar o primeiro token. Assim, mesmo que o DISCOVER
     * inicial seja perdido (UDP não garante entrega), o nó continua tentando
     * se conectar aos demais e ao anel.
     */
    private void bootstrapToken() {
        sleepMillis(1000);
        int discoveryAttempts = 0;
        while (running && peers.size() <= 1) {
            // Rediscover periodicamente enquanto sozinho
            if (++discoveryAttempts % 5 == 0) {
                log("[BOOTSTRAP] rediscovery (tentativa " + discoveryAttempts + ")");
                sendDiscover();
            }
            sleepMillis(1000);
        }
        // Agora temos pelo menos mais 1 nó, tenta gerar token se foor mestre
        while (running && !firstTokenDone) {
            tryStartFirstToken("verificação periódica de inicialização");
            sleepMillis(1000);
        }
    }

    // ===== ENVIO BRUTO PELO SOCKET =====

    private void sendTo(Peer p, String msg) {
        sendRaw(p.address, p.port, msg);
    }

    private void sendRaw(InetAddress addr, int port, String msg) {
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(b, b.length, addr, port);
            synchronized (sendLock) {
                socket.send(pkt);
            }
        } catch (Exception e) {
            log("Falha ao enviar para " + addr + ":" + port + " -> " + e.getMessage());
        }
    }

    // ===== INTERFACE PARA O MENU =====

    public boolean enqueue(String dest, String content) { return queue.add(dest, content); }
    public List<String> queueDescribe() { return queue.describe(); }
    public int queueSize() { return queue.size(); }
    public String ringDiagram() { return peers.diagram(); }
    public boolean isMaster() { return peers.isMaster(); }
    public String masterNick() { return peers.master(); }
    public boolean isAwaitingReturn() { return awaitingReturn; }
    public String selfNick() { return selfNick; }

    // ===== UTILIDADES =====

    /** Corrompe um caractere da mensagem (para simular erro de transmissão). */
    private String corrupt(String s) {
        if (s.isEmpty()) return "#";
        char[] c = s.toCharArray();
        int i = rng.nextInt(c.length);
        c[i] = (c[i] == '#') ? '@' : '#';
        return new String(c);
    }

    private void log(String s) {
        String now = (LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS")));
        Console.println(now + " [" + selfNick + "] " + s);
    }

    private void sleepSeconds(double s) { sleepMillis((long) (s * 1000)); }

    private void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Descobre um IP IPv4 local não-loopback (cai para 127.0.0.1 se não houver). */
    public static String primaryLocalIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}