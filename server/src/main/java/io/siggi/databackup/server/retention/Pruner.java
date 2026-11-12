package io.siggi.databackup.server.retention;

import io.siggi.databackup.server.ServerConfig;
import io.siggi.databackup.server.repository.BackupItem;
import io.siggi.databackup.server.repository.BackupRepositories;
import io.siggi.databackup.server.repository.BackupRepository;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Applies each machine's {@link RetentionPolicy} (from {@link ServerConfig#getRetention(String)}) to its committed
 * backups and deletes the backups the policy doesn't keep. Pending manifests are left alone.
 */
public final class Pruner {

    private final BackupRepositories repositories;
    private final ServerConfig config;
    private final ZoneId zone;
    private final Predicate<String> machineFilter;

    /**
     * @param repositories  the machines' backups
     * @param config        the retention settings
     * @param zone          the time zone days, weeks and months are counted in
     * @param machineFilter which machine folders to consider (others, such as invalid names, are skipped)
     */
    public Pruner(BackupRepositories repositories, ServerConfig config, ZoneId zone, Predicate<String> machineFilter) {
        this.repositories = repositories;
        this.config = config;
        this.zone = zone;
        this.machineFilter = machineFilter;
    }

    /**
     * What pruning did, or would do, for one machine. All lists hold backup names, newest first.
     *
     * @param kept    the backups kept
     * @param deleted the backups deleted (or, on a dry run, that would be deleted)
     * @param failed  the backups that should have been deleted but could not be
     */
    public record MachineResult(String machine, RetentionPolicy policy, List<String> kept, List<String> deleted,
                                List<String> failed) {
    }

    /**
     * Prunes every machine, in name order.
     *
     * @param dryRun if true, only work out what would be deleted
     */
    public List<MachineResult> pruneAll(boolean dryRun) {
        List<String> machines = new ArrayList<>(repositories.listNames());
        machines.removeIf(machineFilter.negate());
        machines.sort(null);
        List<MachineResult> results = new ArrayList<>();
        for (String machine : machines) {
            results.add(prune(machine, dryRun));
        }
        return results;
    }

    /**
     * Prunes one machine's backups.
     *
     * @param dryRun if true, only work out what would be deleted
     */
    public MachineResult prune(String machine, boolean dryRun) {
        RetentionPolicy policy = RetentionPolicy.of(config.getRetention(machine));
        BackupRepository repository = repositories.get(machine);
        List<BackupItem> items = repository.getAllItems();
        RetentionPolicy.Decision<BackupItem> decision = policy.apply(items, BackupItem::getTime, zone);
        List<BackupItem> toDelete = new ArrayList<>(decision.delete());
        // The policy always keeps the newest backup; make sure of it here too.
        BackupItem newest = repository.getMostRecent();
        toDelete.remove(newest);
        List<BackupItem> failed = dryRun ? List.of() : repository.delete(toDelete);
        List<BackupItem> deleted = new ArrayList<>(toDelete);
        deleted.removeAll(failed);
        List<BackupItem> kept = new ArrayList<>(decision.keep());
        if (newest != null && !kept.contains(newest)) kept.add(0, newest);
        return new MachineResult(machine, policy, names(kept), names(deleted), names(failed));
    }

    private static List<String> names(List<BackupItem> items) {
        List<String> names = new ArrayList<>(items.size());
        for (BackupItem item : items) names.add(item.getName());
        return names;
    }
}
