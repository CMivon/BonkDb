import Engine.Cli;

//testing123

/**
 * The one entry point for the whole program. Kept deliberately thin --
 * it just launches the CLI. If bonkDB ever grows a second way to run
 * (a GUI, a script mode, whatever), this is the place that decides
 * which one to start, without Cli needing to know it's "the" entry point.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Cli.main(args);
    }
}