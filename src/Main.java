import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

/**
 * Ponto de entrada e menu interativo da aplicação.
 *
 * Uso:
 *   java Main <arquivo_config> [porta_local=6000] [alvos_descoberta]
 *
 *   - arquivo_config  : arquivo com as 5 linhas de configuração.
 *   - porta_local     : porta UDP em que esta máquina escuta (padrão 6000).
 *   - alvos_descoberta: para onde enviar DISCOVER/HELLO. Padrão: broadcast em
 *                       255.255.255.255:6000. Em testes na mesma máquina,
 *                       informe os pares host:porta, aceitando intervalos:
 *                       "127.0.0.1:6000-6002".
 *
 * Exemplos:
 *   LAN (3 PCs):  java Main config.txt
 *   localhost:    java Main config_A.txt 6000 "127.0.0.1:6000-6002"
 */
public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }
        Config cfg = Config.load(args[0]);
        int port = args.length >= 2 ? Integer.parseInt(args[1].trim()) : Packet.DISCOVER_PORT;
        List<InetSocketAddress> targets = args.length >= 3
                ? parseTargets(args[2])
                : Collections.singletonList(new InetSocketAddress(
                        InetAddress.getByName("255.255.255.255"), Packet.DISCOVER_PORT));

        RingNode node = new RingNode(cfg, port, targets);
        node.start();
        runMenu(node);
    }

    private static void printUsage() {
        System.out.println("Uso: java Main <arquivo_config> [porta_local=6000] [alvos_descoberta]");
        System.out.println("  LAN (3 PCs):  java Main config.txt");
        System.out.println("  localhost:    java Main config_A.txt 6000 \"127.0.0.1:6000-6002\"");
    }

    /** Interpreta "host:porta" ou "host:inicio-fim", separados por vírgula. */
    private static List<InetSocketAddress> parseTargets(String spec) throws Exception {
        List<InetSocketAddress> list = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int colon = part.lastIndexOf(':');
            String host = part.substring(0, colon);
            String portPart = part.substring(colon + 1);
            InetAddress addr = InetAddress.getByName(host);
            if (portPart.contains("-")) {
                String[] r = portPart.split("-");
                int a = Integer.parseInt(r[0].trim());
                int b = Integer.parseInt(r[1].trim());
                for (int p = a; p <= b; p++) list.add(new InetSocketAddress(addr, p));
            } else {
                list.add(new InetSocketAddress(addr, Integer.parseInt(portPart.trim())));
            }
        }
        return list;
    }

    private static void printMenu(RingNode node) {
        System.out.println();
        System.out.println("==================== MENU (" + node.selfNick() + ") ====================");
        System.out.println(" Anel: " + node.ringDiagram() + "   | mestre: " + node.masterNick()
                + (node.isMaster() ? " (sou eu)" : ""));
        System.out.println(" 1) Enviar mensagem (unicast)");
        System.out.println(" 2) Enviar mensagem em BROADCAST");
        System.out.println(" 3) Inserir/gerar token na rede");
        System.out.println(" 4) Retirar token da rede");
        System.out.println(" 5) Mostrar estado (anel, fila, token)");
        System.out.println(" 6) Reenviar DISCOVER (redescobrir o anel)");
        System.out.println(" 0) Sair");
        System.out.println("=========================================================");
        System.out.print("> ");
    }

    private static void runMenu(RingNode node) {
        Scanner sc = new Scanner(System.in);
        printMenu(node);
        while (true) {
            if (!sc.hasNextLine()) {
                // Sem console (stdin fechado): mantém o nó rodando em segundo plano.
                sleep(60000);
                continue;
            }
            Console.pause();
            System.out.print("> ");
            String line = sc.nextLine().trim();
            Console.resume();
            if (line.isEmpty()) { printMenu(node); continue; }
            switch (line) {
                case "1": {
                    Console.pause();
                    System.out.print("Apelido do destino: ");
                    String dest = sc.hasNextLine() ? sc.nextLine().trim() : "";
                    System.out.print("Mensagem: ");
                    String msg = sc.hasNextLine() ? sc.nextLine() : "";
                    Console.resume();
                    if (dest.isEmpty()) { System.out.println("Destino inválido."); break; }
                    boolean ok = node.enqueue(dest, msg);
                    System.out.println(ok ? "Mensagem enfileirada para '" + dest + "'."
                            : "Fila cheia (máx. " + MessageQueue.MAX + ").");
                    break;
                }
                case "2": {
                    Console.pause();
                    System.out.print("Mensagem (broadcast): ");
                    String msg = sc.hasNextLine() ? sc.nextLine() : "";
                    Console.resume();
                    boolean ok = node.enqueue(Packet.BROADCAST, msg);
                    System.out.println(ok ? "Mensagem broadcast enfileirada."
                            : "Fila cheia (máx. " + MessageQueue.MAX + ").");
                    break;
                }
                case "3": node.insertToken(); break;
                case "4": node.requestRemoveToken(); break;
                case "5": {
                    System.out.println("Anel:   " + node.ringDiagram());
                    System.out.println("Mestre: " + node.masterNick() + (node.isMaster() ? " (sou eu)" : ""));
                    System.out.println("Aguardando retorno de dados: " + node.isAwaitingReturn());
                    List<String> q = node.queueDescribe();
                    System.out.println("Fila (" + node.queueSize() + "/" + MessageQueue.MAX + "):");
                    if (q.isEmpty()) System.out.println("  (vazia)");
                    else for (String s : q) System.out.println("  " + s);
                    break;
                }
                case "6": node.sendDiscover(); break;
                case "0": System.out.println("Encerrando."); System.exit(0); break;
                default: System.out.println("Opção inválida.");
            }
            printMenu(node);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
