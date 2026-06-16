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
 *   - bootstrap: após a descoberta estabilizar, faz a máquina inicial gerar
 *                o primeiro token.
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
            case Packet.TOKEN:    onToken();                     break;
            case Packet.DATA:     onData(raw);                   break;
            default:              log("Pacote desconhecido recebido: " + raw);
        }
    }

    private void onDiscover(String raw, InetAddress src, int srcPort) {
        String[] p = raw.split(":", 3);
        if (p.length < 2) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return; // ignora a si mesmo
        boolean changed = peers.addOrUpdate(nick, src, srcPort);
        if (changed) {
            log("DISCOVER de '" + nick + "' (" + src.getHostAddress() + ":" + srcPort
                    + "). Nova topologia: " + peers.diagram());
        }
        sendHello(); // responde se identificando (em broadcast)
    }

    private void onHello(String raw, InetAddress src, int srcPort) {
        String[] p = raw.split(":", 3);
        if (p.length < 2) return;
        String nick = p[1];
        if (nick.equals(selfNick)) return;
        boolean changed = peers.addOrUpdate(nick, src, srcPort);
        if (changed) {
            log("HELLO de '" + nick + "' (" + src.getHostAddress() + ":" + srcPort
                    + "). Nova topologia: " + peers.diagram());
        }
    }

    // ============================== TOKEN ==============================

    private void onToken() {
        lastRingActivity = System.currentTimeMillis();

        // Controle do token feito pela máquina mestre: detecta DOIS tokens.
        if (peers.isMaster()) {
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
        Peer s = peers.successor();
        if (s == null) {
            log("Sem sucessor; token retido.");
            return;
        }
        log("[TOKEN] repassado para '" + s.nickname + "'.");
        sendTo(s, Packet.token());
    }

    /** Opção de menu: gerar/inserir um token na rede (qualquer máquina). */
    public void insertToken() {
        lastRingActivity = System.currentTimeMillis();
        firstTokenDone = true;
        Peer s = peers.successor();
        if (s == null) {
            log("Sem sucessor; não foi possível inserir token.");
            return;
        }
        log("[TOKEN] inserido na rede pelo usuário; enviado para '" + s.nickname + "'.");
        sendTo(s, Packet.token());
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

    // ===================== MONITOR DO TOKEN =====================

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

    /** Após a descoberta estabilizar, a máquina inicial gera o primeiro token. */
    private void bootstrapToken() {
        sleepMillis(3000);
        if (!firstTokenDone && peers.isMaster()) {
            firstTokenDone = true;
            lastRingActivity = System.currentTimeMillis();
            lastTokenAtMaster = System.currentTimeMillis();
            log("[MONITOR] sou a máquina inicial do anel ('" + selfNick + "'). Gerando o primeiro token.");
            forwardToken();
        } else {
            firstTokenDone = true; // já há (ou haverá) token gerado por outra máquina
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
        System.out.println("[" + selfNick + "] " + s);
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
