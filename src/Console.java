import java.util.ArrayList;
import java.util.List;

/**
 * Console helper que permite pausar a escrita de logs na saída enquanto o
 * usuário digita, acumulando mensagens e liberando-as depois.
 */
public class Console {
    private static final Object lock = new Object();
    private static boolean paused = false;
    private static final List<String> buffer = new ArrayList<>();

    public static void println(String s) {
        synchronized (lock) {
            if (paused) {
                buffer.add(s);
                return;
            }
            System.out.println(s);
        }
    }

    public static void print(String s) {
        synchronized (lock) {
            if (paused) {
                // preserve as a single buffered line
                if (buffer.isEmpty()) buffer.add(s);
                else {
                    int last = buffer.size() - 1;
                    buffer.set(last, buffer.get(last) + s);
                }
                return;
            }
            System.out.print(s);
        }
    }

    public static void pause() {
        synchronized (lock) { paused = true; }
    }

    public static void resume() {
        synchronized (lock) {
            paused = false;
            if (!buffer.isEmpty()) {
                for (String l : buffer) System.out.println(l);
                buffer.clear();
            }
        }
    }
}
