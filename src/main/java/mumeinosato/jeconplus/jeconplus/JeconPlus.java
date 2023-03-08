package mumeinosato.jeconplus.jeconplus;

import jp.jyn.jbukkitlib.command.SubExecutor;
import jp.jyn.jbukkitlib.uuid.UUIDRegistry;
import mumeinosato.jeconplus.jeconplus.command.Convert;
import mumeinosato.jeconplus.jeconplus.command.Create;
import mumeinosato.jeconplus.jeconplus.command.Give;
import mumeinosato.jeconplus.jeconplus.command.Help;
import mumeinosato.jeconplus.jeconplus.command.Pay;
import mumeinosato.jeconplus.jeconplus.command.Reload;
import mumeinosato.jeconplus.jeconplus.command.Remove;
import mumeinosato.jeconplus.jeconplus.command.Set;
import mumeinosato.jeconplus.jeconplus.command.Show;
import mumeinosato.jeconplus.jeconplus.command.Take;
import mumeinosato.jeconplus.jeconplus.command.Top;
import mumeinosato.jeconplus.jeconplus.command.Version;
import mumeinosato.jeconplus.jeconplus.config.ConfigLoader;
import mumeinosato.jeconplus.jeconplus.config.MainConfig;
import mumeinosato.jeconplus.jeconplus.config.MessageConfig;
import mumeinosato.jeconplus.jeconplus.db.Database;
import mumeinosato.jeconplus.jeconplus.repository.BalanceRepository;
import mumeinosato.jeconplus.jeconplus.repository.LazyRepository;
import mumeinosato.jeconplus.jeconplus.repository.SyncRepository;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.event.Listener;
import java.util.UUID;
import java.util.function.Consumer;

public class JeconPlus extends JavaPlugin {
    private static JeconPlus instance = null;

    private ConfigLoader config;
    private BalanceRepository repository;
    private VaultEconomy economy;

    // Stack(LIFO)
    private final Deque<Runnable> destructor = new ArrayDeque<>();

    @Override
    public void onEnable() {
        instance = this;
        destructor.clear();

        if (config == null) {
            config = new ConfigLoader();
        }
        config.reloadConfig();
        MainConfig main = config.getMainConfig();
        MessageConfig message = config.getMessageConfig();

        UUIDRegistry registry = UUIDRegistry.getSharedCacheRegistry(this);

        VersionChecker checker = new VersionChecker(main.versionCheck, message);
        BukkitTask task = getServer().getScheduler().runTaskLater(
                this,
                () -> checker.check(Bukkit.getConsoleSender()), 20 * 30
        );
        destructor.addFirst(task::cancel);

        // connect db
        Database db = Database.connect(main.database);
        destructor.addFirst(db::close);

        // methods for internal use.
        Consumer<UUID> consistency;
        Consumer<UUID> save;
        Runnable saveAll;
        // init repository
        if (main.lazyWrite) {
            LazyRepository lazy = new LazyRepository(main, db);
            repository = lazy;

            consistency = lazy::consistency;
            save = lazy::save;
            saveAll = lazy::saveAll;
        } else {
            repository = new SyncRepository(main, db);

            consistency = u -> {};
            save = u -> {};
            saveAll = () -> {};
        }
        destructor.addFirst(() -> {
            saveAll.run();
            repository = null;
        });

        // register vault
        if (economy == null) {
            Plugin vault = getServer().getPluginManager().getPlugin("Vault");
            if (vault != null) {
                if (vault.isEnabled()) {
                    vaultHook(registry);
                } else {
                    getServer().getPluginManager().registerEvents(new VaultRegister(registry), this);
                }
            }
        } else {
            economy.init(main, registry, repository);
        }

        // register events
        getServer().getPluginManager().registerEvents(
                new EventListener(main, checker, repository, consistency, save), this
        );
        destructor.addFirst(() -> HandlerList.unregisterAll(this));

        // register commands
        SubExecutor.Builder builder = SubExecutor.Builder.init()
                .setDefaultCommand("show")
                .putCommand("show", new Show(message, registry, repository))
                .putCommand("pay", new Pay(message, registry, repository))
                .putCommand("set", new Set(message, registry, repository))
                .putCommand("give", new Give(message, registry, repository))
                .putCommand("take", new Take(message, registry, repository))
                .putCommand("create", new Create(main, message, registry, repository))
                .putCommand("remove", new Remove(message, registry, repository))
                .putCommand("top", new Top(message, registry, repository))
                .putCommand("convert", new Convert(config, repository, db, saveAll))
                .putCommand("reload", new Reload(message))
                .putCommand("version", new Version(message, checker));
        Help help = new Help(message, builder.getSubCommands());
        builder.setErrorExecutor(help).putCommand("help", help);

        PluginCommand cmd = getCommand("jecon");
        SubExecutor subExecutor = builder.register(cmd);
        destructor.addFirst(() -> {
            cmd.setTabCompleter(this);
            cmd.setExecutor(this);
        });
    }

    private void vaultHook(UUIDRegistry registry) {
        if (economy != null) {
            return;
        }

        economy = new VaultEconomy(config.getMainConfig(), registry, repository);
        getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.Normal);
        getLogger().info("Hooked Vault");
    }

    @Override
    public void onDisable() {
        while (!destructor.isEmpty()) {
            destructor.removeFirst().run();
        }
    }

    /**
     * Get Jecon instance
     *
     * @return Jecon
     */
    public static JeconPlus getInstance() {
        return instance;
    }

    /**
     * Get BalanceRepository
     *
     * @return BalanceRepository
     */
    public BalanceRepository getRepository() {
        return repository;
    }

    private static class VaultRegister implements Listener {
        private final UUIDRegistry registry;

        private VaultRegister(UUIDRegistry registry) {
            this.registry = registry;
        }

        @EventHandler(ignoreCancelled = true)
        public void onPluginEnable(PluginEnableEvent e) {
            if (!e.getPlugin().getName().equals("Vault")) {
                return;
            }
            JeconPlus.getInstance().vaultHook(registry);
            PluginEnableEvent.getHandlerList().unregister(this);
        }
    }
}
