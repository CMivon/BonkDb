package Art;
/**
 * Standalone ASCII art for bonkDB. Not part of the database engine --
 * just a fun little mascot you can pull into the CLI or anywhere else.
 */
public class Art {

    public static String bonkMascot() {
        return "        .-\"\"\"\"-.\n" +
               "       /        \\\n" +
               "      |  o    o  |\n" +
               "      |    <>    |\n" +
               "       \\  ----  /\n" +
               "        '-.__.-'\n" +
               "       /   ||   \\\n" +
               "      /    ||    \\\n" +
               "     |   BONK!    |\n" +
               "      \\   ||    /\n" +
               "       '--''--'\n" +
               "      /        \\\n" +
               "     /          \\\n" +
               "    '            '\n";
    }

    public static void main(String[] args) {
        System.out.println(bonkMascot());
        System.out.println("      bonkDB -- databases, bonked into shape.");
    }
}