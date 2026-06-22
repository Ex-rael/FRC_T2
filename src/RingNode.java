import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
    private final Object sendLock = new Object();

    // ---- Estado do token / dados (compartilhado entre threads) ----
    private volatile boolean removeTokenRequested = false; // usuário pediu retirada do token
    private volatile boolean awaitingReturn = false;       // origem aguardando os dados darem a volta
    private volatile long dataSentAt = 0L;                  // instante do último envio de dados
    private volatile long lastRingActivity = System.currentTimeMillis(); // último token/dado visto
    private volatile long lastTokenAtMaster = 0L;          // última passagem do token pelo mestre
    private volatile boolean firstTokenDone = false;       // já existe token na rede?
    private volatile boolean running = true;

    public RingNode(Config cfg, int bindPort, List<InetSocketAddress> discoveryTargets) throws Exception {
        this.cfg = cfg;
        this.selfNick = cfg.nickname;
        this.bindPort = bindPort;
        this.discoveryTargets = discoveryTargets;
        this.selfIp = primaryLocalIp();

        // Socket UDP ligado à porta local, com reuso de endereço e broadcast.
        this.socket = new DatagramSocket(null);
        this.socket.setReuseAddress(true);
        this.socket.setBroadcast(true);
        this.socket.bind(new InetSocketAddress(bindPort));

        this.peers = new PeerRegistry(selfNick, InetAddress.getByName(selfIp), bindPort);
    }

    public void start() {
        log("Máquina '" + selfNick + "' iniciada em " + selfIp + ":" + bindPort + " | " + cfg);
        startDaemon(this::receiveLoop, "receiver");
        startDaemon(this::monitorLoop, "monitor");
        startDaemon(this::bootstrapToken, "bootstrap");
        sendDiscover();
    }

    private void startDaemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    // ===================== DESCOBERTA (DISCOVER/HELLO) =====================

    /** Envia DISCOVER (broadcast) se identificando. */
    public void sendDiscover() {
        broadcast(Packet.discover(selfNick, selfIp));
        log("DISCOVER enviado (procurando outras máquinas).");
    }

    private void sendHello() {
        broadcast(Packet.hello(selfNick, selfIp));
    }

    private void broadcast(String msg) {
        for (InetSocketAddress t : discoveryTargets) {
            sendRaw(t.getAddress(), t.getPort(), msg);
        }
    }

    // ============================ RECEPÇÃO ============================

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
                String type = Packet.typeOf(raw);
                if (Packet.TOKEN.equals(type) || Packet.DATA.equals(type) || Packet.DISCOVER.equals(type)
                        || Packet.HELLO.equals(type)) {
                    log("[RECV] " + pkt.getAddress().getHostAddress() + ":" + pkt.getPort()
                            + " -> " + type + " " + raw);
                }
                // Marca peer como visto (atualiza lastSeen) mesmo para pacotes
                // que não trazem apelido (TOKEN), usando endereço/porta.
                peers.markSeenByAddr(pkt.getAddress(), pkt.getPort(), System.currentTimeMillis());
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
            case Packet.CLAIM:    onClaim(raw, src, srcPort);    break;
            case Packet.TOKEN:    onToken();                     break;
            case Packet.DATA:     onData(raw);                   break;
            default:              log("Pacote desconhecido recebido: " + raw);
        }
    }

    private volatile boolean claimLost = false;

    private void onClaim(String raw, InetAddress src, int srcPort) {
        String[] p = raw.split(":", 4);
        if (p.length < 3) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return;
        long theirInsertion = 0L;
        try { theirInsertion = Long.parseLong(p[2]); } catch (Exception ignored) {}

        long now = System.currentTimeMillis();
        long peerStaleMs = (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000);
        peers.addOrUpdate(nick, src, srcPort, now, peerStaleMs);

        // If we are currently trying to claim the token and the other peer has
        // priority (earlier insertionTime or tie-broken by nickname), then
        // mark claimLost so the claiming thread aborts.
        Peer selfPeer = peers.get(selfNick);
        long myInsertion = (selfPeer != null) ? selfPeer.insertionTime : 0L;
        boolean otherHasPriority = false;
        if (theirInsertion > 0) {
            if (theirInsertion < myInsertion) otherHasPriority = true;
            else if (theirInsertion == myInsertion && nick.compareTo(selfNick) < 0) otherHasPriority = true;
        }
        if (otherHasPriority) {
            synchronized (this) {
                claimLost = true;
                this.notifyAll();
            }
        }
    }

    private void onDiscover(String raw, InetAddress src, int srcPort) {
        String[] p = raw.split(":", 3);
        if (p.length < 2) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return; // ignora a si mesmo
        long now = System.currentTimeMillis();
        long peerStaleMs = (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000);
        boolean changed = peers.addOrUpdate(nick, src, srcPort, now, peerStaleMs);
        if (changed) {
            log("DISCOVER de '" + nick + "' (" + src.getHostAddress() + ":" + srcPort
                    + "). Nova topologia: " + peers.diagram());
            tryStartFirstToken("nova máquina descoberta: '" + nick + "'");
        }
        sendHello(); // responde se identificando (em broadcast)
    }

    private void onHello(String raw, InetAddress src, int srcPort) {
        String[] p = raw.split(":", 3);
        if (p.length < 2) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return;
        long now = System.currentTimeMillis();
        long peerStaleMs = (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000);
        boolean changed = peers.addOrUpdate(nick, src, srcPort, now, peerStaleMs);
        if (changed) {
            log("HELLO de '" + nick + "' (" + src.getHostAddress() + ":" + srcPort
                    + "). Nova topologia: " + peers.diagram());
            tryStartFirstToken("nova máquina descoberta: '" + nick + "'");
        }
    }

    // ============================== TOKEN ==============================

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
        sleepSeconds(cfg.tokenTime); // segura o token ("tempo do token") para visualização

        OutgoingMessage m = queue.peek();
        if (m != null) {
            sendData(m); // detém o token até os dados retornarem à origem
        } else {
            forwardToken();
        }
    }

    /** Repassa o token para o sucessor do anel. */
    private void forwardToken() {
        awaitingReturn = false;
        long now = System.currentTimeMillis();
        long peerStaleMs = (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000);
        Peer s = peers.successorAlive(now - peerStaleMs);
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
    * (a primeira que entrou na rede) e ainda não exista token na rede.
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

        // Para evitar geração simultânea de tokens por mestres diferentes
        // (condição possível com perda de pacotes UDP), usamos um protocolo
        // leve de CLAIM: anunciamos nossa intenção com nosso insertionTime
        // e aguardamos um curto período; se outro nó com prioridade responder
        // ou anunciar, abortamos.
        long requestTime = System.currentTimeMillis();
        double backoffMin = Math.max(0.0, cfg.claimBackoffMin);
        double backoffMax = Math.max(backoffMin, cfg.claimBackoffMax);
        int delayMs = (int) Math.round((backoffMin + rng.nextDouble() * (backoffMax - backoffMin)) * 1000);
        if (delayMs <= 0) delayMs = 1;
        Peer selfPeer = peers.get(selfNick);
        long myInsertion = (selfPeer != null) ? selfPeer.insertionTime : System.currentTimeMillis();
        claimLost = false;
        String claimMsg = Packet.claim(selfNick, myInsertion);
        log("[MONITOR] anunciando CLAIM (insertion=" + myInsertion + ") e aguardando " + delayMs + "ms (" + motivo + ").");

        int sent = 0;
        int intervalMs = 250;
        while (sent < delayMs && !claimLost && !firstTokenDone) {
            broadcast(claimMsg);
            int stepMs = Math.min(intervalMs, delayMs - sent);
            try {
                this.wait(stepMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sent += stepMs;
        }

        // Rechecagens após o backoff
        if (firstTokenDone) return; // outro thread já gerou
        if (!peers.isMaster()) { firstTokenDone = true; return; }
        if (claimLost) {
            firstTokenDone = true; // outro nó tem prioridade; aborta
            log("[MONITOR] CLAIM perdido para outro nó; abortando geração do token.");
            return;
        }
        // Se houve atividade recente no anel durante o backoff, aborta.
        if (lastRingActivity > requestTime) {
            firstTokenDone = true; // há token em circulação
            log("[MONITOR] atividade detectada durante backoff; abortando geração do token.");
            return;
        }

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

    // ============================== DADOS ==============================

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
        long now = System.currentTimeMillis();
        long peerStaleMs = (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000);
        Peer s = peers.successorAlive(now - peerStaleMs);
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
        long now = System.currentTimeMillis();
        long peerStaleMs = (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000);
        Peer s = peers.successorAlive(now - peerStaleMs);
        if (s == null) {
            log("Sem sucessor vivo; dados descartados.");
            return;
        }
        log("[DADOS] repassado para '" + s.nickname + "'.");
        sendTo(s, raw);
    }

    // ===================== MONITOR DO TOKEN =====================

    private void monitorLoop() {
        while (running) {
            sleepMillis(200);
            long now = System.currentTimeMillis();

            // Recuperação de dados presos (qualquer origem): se os dados não
            // retornarem em tempo, libera o token para não travar o anel.
            if (awaitingReturn && dataSentAt > 0
                    && now - dataSentAt > (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000)) {
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
            // Remover peers possivelmente inativos da tabela para manter ordem correta.
            long peerStaleMs = (long) (cfg.tokenTimeout * cfg.peerStaleMultiplier * 1000);
            boolean removed = peers.pruneStale(now - peerStaleMs);
            if (removed) {
                log("[MONITOR] peers inativos removidos; nova topologia: " + peers.diagram());
            }
        }
    }

    /**
     * Mecanismo de reserva: tenta gerar o primeiro token periodicamente até
     * conseguir (mestre + ao menos 2 máquinas no anel) ou até outra máquina
     * assumir esse papel. Normalmente a geração já acontece antes disso, de
     * forma reativa, em tryStartFirstToken() chamado a partir de
     * onDiscover/onHello.
     */
    private void bootstrapToken() {
        sleepMillis((long) (cfg.discoverInterval * 1000));
        while (running) {
            if (!firstTokenDone) {
                tryStartFirstToken("verificação periódica de inicialização");
            }
            // Garantir descoberta periódica de peers em ambientes UDP ruidosos.
            sendDiscover();
            sleepMillis((long) (cfg.discoverInterval * 1000));
        }
    }

    // ===================== ENVIO BRUTO PELO SOCKET =====================

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

    // ===================== INTERFACE PARA O MENU =====================

    public boolean enqueue(String dest, String content) { return queue.add(dest, content); }
    public List<String> queueDescribe() { return queue.describe(); }
    public int queueSize() { return queue.size(); }
    public String ringDiagram() { return peers.diagram(); }
    public boolean isMaster() { return peers.isMaster(); }
    public String masterNick() { return peers.master(); }
    public boolean isAwaitingReturn() { return awaitingReturn; }
    public String selfNick() { return selfNick; }

    // ===================== UTILIDADES =====================

    /** Corrompe um caractere da mensagem (para simular erro de transmissão). */
    private String corrupt(String s) {
        if (s.isEmpty()) return "#";
        char[] c = s.toCharArray();
        int i = rng.nextInt(c.length);
        c[i] = (c[i] == '#') ? '@' : '#';
        return new String(c);
    }

    private void log(String s) {
        Console.println("[" + selfNick + "] " + s);
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