/**
 * Representa um pacote de dados já interpretado a partir da string recebida.
 * Formato: "2000:origem:destino:controle:crc:mensagem".
 *
 * A divisão usa limite 6 para que a própria mensagem possa conter ':'
 * (apenas os cinco primeiros separadores são significativos).
 */
public class DataPacket {
    public String origem;
    public String destino;
    public String controle;  // maquinainexistente | ACK | NAK
    public long crc;         // controle de erro (CRC32) calculado pela origem
    public String message;

    public static DataPacket parse(String raw) {
        String[] p = raw.split(":", 6);
        if (p.length < 6) return null;
        DataPacket d = new DataPacket();
        d.origem = p[1];
        d.destino = p[2];
        d.controle = p[3];
        try {
            d.crc = Long.parseLong(p[4].trim());
        } catch (NumberFormatException e) {
            d.crc = -1; // CRC inválido -> provocará NAK no destino
        }
        d.message = p[5];
        return d;
    }

    /** Reconstrói a string do pacote (com eventuais campos alterados). */
    public String build() {
        return Packet.data(origem, destino, controle, crc, message);
    }
}
