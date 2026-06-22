public class Console {
    private static final Object lock = new Object();
    private static String prompt = null;

    public static void setPrompt(String p) {
        synchronized (lock) {
            prompt = p;
        }
    }

    public static void clearPrompt() {
        synchronized (lock) {
            prompt = null;
        }
    }

    public static void println(String s) {
        synchronized (lock) {
            System.out.println(s);
            if (prompt != null) {
                System.out.print(prompt);
                System.out.flush();
            }
        }
    }

    public static void print(String s) {
        synchronized (lock) {
            System.out.print(s);
            System.out.flush();
        }
    }
}
