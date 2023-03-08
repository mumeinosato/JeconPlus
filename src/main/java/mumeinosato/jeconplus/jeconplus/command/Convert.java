package mumeinosato.jeconplus.jeconplus.command;

import jp.jyn.jbukkitlib.command.SubCommand;
import jp.jyn.jbukkitlib.config.YamlLoader;
import mumeinosato.jeconplus.jeconplus.JeconPlus;
import mumeinosato.jeconplus.jeconplus.config.ConfigLoader;
import mumeinosato.jeconplus.jeconplus.config.MainConfig;
import mumeinosato.jeconplus.jeconplus.db.Database;
import mumeinosato.jeconplus.jeconplus.repository.BalanceRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Optional;
import java.util.Queue;

public class Convert extends SubCommand {
    private final ConfigLoader loader;
    private final BalanceRepository repository;
    private final Database oldDB;
    private final MainConfig.DatabaseConfig old;
    private final Runnable save;

    public Convert(ConfigLoader loader, BalanceRepository repository, Database oldDB, Runnable save) {
        this.loader = loader;
        this.repository = repository;
        this.oldDB = oldDB;
        this.old = loader.getMainConfig().database;
        this.save = save;
    }

    @Override
    protected Result onCommand(CommandSender sender, Queue<String> args) {
        // 割と強引
        YamlLoader config = new YamlLoader(JeconPlus.getInstance(), "config.yml");
        if (Optional.ofNullable(args.poll()).map(a -> a.equalsIgnoreCase("confirm")).orElse(false)) {
            convert(sender, config);
        } else {
            confirm(sender, config);
        }
        return Result.OK;
    }

    private void confirm(CommandSender sender, YamlLoader config) {
        ConfigurationSection db = config.getConfig().getConfigurationSection("database");

        if (old.url.startsWith("jdbc:sqlite:")) {
            sender.sendMessage("Convert from SQLite to MySQL");
            sender.sendMessage("Convert to:");
            sender.sendMessage("Host: " + db.getString("mysql.host"));
            sender.sendMessage("Name: " + db.getString("mysql.name"));
            sender.sendMessage("User: " + db.getString("mysql.username"));
            sender.sendMessage("Pass: " + db.getString("mysql.password"));
        } else if (old.url.startsWith("jdbc:mysql:")) {
            sender.sendMessage("Convert from MySQL to SQLite");
            sender.sendMessage("Convert to:");
            sender.sendMessage("File: " + db.getString("sqlite.file"));
        } else {
            sender.sendMessage("Unsupported Database");
            return;
        }

        sender.sendMessage("");
        sender.sendMessage("All data in the destination database is deleted.");
        sender.sendMessage("Please be sure to back up.");
        sender.sendMessage("");
        sender.sendMessage("If there is no problem, please execute '/money convert confirm'.");
        sender.sendMessage("If you need to change the settings, edit config.yml.");
    }

    private void convert(CommandSender sender, YamlLoader config) {
        if (old.url.startsWith("jdbc:sqlite:")) {
            config.getConfig().set("database.type", "mysql");
        } else if (old.url.startsWith("jdbc:mysql:")) {
            config.getConfig().set("database.type", "sqlite");
        } else {
            sender.sendMessage("Unsupported Database");
            return;
        }
        config.saveConfig();
        sender.sendMessage("Config reloading.");
        loader.reloadConfig();

        sender.sendMessage("Connect to database.");
        Database db = Database.connect(loader.getMainConfig().database);

        sender.sendMessage("Saving unsaved data.");
        save.run();

        sender.sendMessage("Converting...");
        db.convert(oldDB);
        sender.sendMessage("Converted.");

        sender.sendMessage("Reloading...");
        JeconPlus jecon = JeconPlus.getInstance();
        jecon.onDisable();
        db.close();
        jecon.onEnable();
        sender.sendMessage("Successfully completed.");
    }

    @Override
    protected String requirePermission() {
        return "jecon.convert";
    }
}
