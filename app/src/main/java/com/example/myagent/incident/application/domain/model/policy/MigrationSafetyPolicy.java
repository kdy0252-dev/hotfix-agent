package com.example.myagent.incident.application.domain.model.policy;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MigrationSafetyPolicy {
    private static final Set<String> MIGRATION_MARKERS = Set.of(
        "migration", "liquibase", "changelog"
    );
    private static final Pattern BREAKING_ADDITION = Pattern.compile(
        "(?i)(drop(?:table|column|index|constraint|foreignkeyconstraint|uniqueconstraint)"
            + "|rename(?:table|column)|modifydatatype|addnotnullconstraint"
            + "|addforeignkeyconstraint|adduniqueconstraint|create\\s+unique\\s+index"
            + "|alter\\s+table.+(?:drop|rename|alter\\s+column|add\\s+constraint)"
            + "|\\b(?:delete\\s+from|update\\s+\\S+\\s+set|truncate\\s+table)\\b"
            + "|\\bnot\\s+null\\b)"
    );

    private MigrationSafetyPolicy() {
    }

    public static boolean isMigrationPath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.endsWith(".sql")
            || MIGRATION_MARKERS.stream().anyMatch(normalized::contains);
    }

    public static boolean isBackwardCompatibleDiff(String diff) {
        return diff.lines().noneMatch(MigrationSafetyPolicy::breaksCompatibility);
    }

    private static boolean breaksCompatibility(String line) {
        if (line.startsWith("---") || line.startsWith("+++")) {
            return false;
        }
        if (line.startsWith("-") && !line.substring(1).isBlank()) {
            return true;
        }
        return line.startsWith("+") && BREAKING_ADDITION.matcher(line.substring(1)).find();
    }
}
