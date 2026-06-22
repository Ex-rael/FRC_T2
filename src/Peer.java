import java.net.InetAddress;

/**
 * Representa uma máquina conhecida no anel (endpoint UDP: IP + porta).
 * O apelido é a chave lógica; o endereço/porta são aprendidos a partir
 * da origem dos pacotes DISCOVER/HELLO recebidos.
 */
public class Peer {
    public final String nickname;
    public volatile InetAddress address;
    public volatile int port;
    public volatile long lastSeen;
    public volatile long birthTime; // instante (epoch ms) em que a máquina entrou na rede

    public Peer(String nickname, InetAddress address, int port, long birthTime) {
        this.nickname = nickname;
        this.address = address;
        this.port = port;
        this.lastSeen = System.currentTimeMillis();
        this.birthTime = birthTime;
    }

    @Override
    public String toString() {
        return nickname + "@" + address.getHostAddress() + ":" + port;
    }
}
