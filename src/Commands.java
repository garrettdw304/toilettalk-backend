import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class Commands {
    private static final Map<String, CommandHandler> HANDLERS = new HashMap<>();
    private static final Map<String, String> HELPERS = new HashMap<>();

    static {{
        addCommand("exit", (_) -> {},
                "exit\n\tCloses the application.");
        addCommand("help", Commands::helpHandler,
                "help [command]\n\tDisplays help information on commands.");
        addCommand("test", Commands::testHandler,
                """
                        test <subcmd>
                        \tSome helpful testing functions.
                        \t\tsubcmd == delete -> Deletes all data.
                        \t\tsubcmd == keys -> Generates RSA keys in public.key and private.key files.
                        \t\tsubcmd == data -> Generates testing data and stores it into the database.""");
    }};

    /**
     * @return True if exit was called, false otherwise.
     */
    public static boolean execute(String input) {
        Scanner scanner = new Scanner(input);
        if (!scanner.hasNext())
            return false;

        String command = scanner.next().toLowerCase();
        if (command.equals("exit"))
            return true;

        CommandHandler handler = HANDLERS.get(command);
        if (handler != null)
            handler.handle(scanner);
        else
            System.out.println("Invalid command. Type 'help' for info on commands.");

        return false;
    }

    public static void Repl() {
        while (true)
            if (execute(System.console().readLine()))
                return;
    }

    private static void addCommand(String command, CommandHandler handler, String helpMsg) {
        HANDLERS.put(command, handler);
        HELPERS.put(command, helpMsg);
    }

    //---COMMAND HANDLERS-----------------------------------------------------------------------------------------------

    private static void helpHandler(Scanner args) {
        if (args.hasNext()) {
            String help = HELPERS.get(args.next());
            if (help != null) {
                System.out.println(help);
                return;
            }
        }

        for (String help : HELPERS.values())
            System.out.println(help);
    }

    private static void testHandler(Scanner args) {
        if (!args.hasNext()) {
            helpHandler(new Scanner("test"));
            return;
        }

        String subcmd = args.next();
        switch (subcmd) {
            case "delete" -> Testing.deleteAllData();
            case "keys" -> Testing.genRSAKeys();
            case "data" -> Testing.genTestingCollections();
            default -> System.out.println("Invalid arguments. Try 'help test'.");
        }
    }

    private interface CommandHandler { void handle(Scanner args); }
}
