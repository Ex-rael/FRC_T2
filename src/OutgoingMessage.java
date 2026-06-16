/**
 * Mensagem armazenada na fila de saída de uma máquina. Guarda também o
 * apelido do destino, conforme exigido pelo enunciado.
 *
 * O conteúdo "original" (correto) é sempre preservado. Quando ocorre um NAK,
 * a mensagem é marcada para ser retransmitida uma única vez SEM erro
 * (forceNoError), e {@code retransmitScheduled} evita retransmissões infinitas.
 */
public class OutgoingMessage {
    public final String dest;            // apelido do destino (ou BROADCAST)
    public final String original;        // conteúdo correto da mensagem
    public boolean forceNoError = false; // retransmitir sem inserir erro (após NAK)
    public boolean retransmitScheduled = false; // já agendou a retransmissão única?

    public OutgoingMessage(String dest, String content) {
        this.dest = dest;
        this.original = content;
    }
}
