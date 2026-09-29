package com.training;

import java.util.*;

/**
 * Trusted server-only intake for the two approved supplemental manager-office members.
 * Does not register routes, replace the original import manifest, initialize a database,
 * publish organization configuration, create teacher profiles or activate accounts.
 * The host performs its already-authorized account review and supplies normal import decisions.
 */
public final class OrganizationManagementSupplementHost {
    private final OrganizationAccountImport importer;

    public OrganizationManagementSupplementHost(OrganizationManagementSupplementSource.Config sources) {
        importer = OrganizationAccountImport.managementSupplement(Objects.requireNonNull(sources));
    }

    public Map<String,Object> preview(Auth.Session actor) throws Exception { return importer.preview(actor); }

    /** Explicit existing-account lookup only; no name or employee-code account guessing. */
    public Map<String,Object> account(Auth.Session actor, long accountId) throws Exception {
        return importer.account(actor, accountId);
    }

    /** Original transactional commit and receipt mechanism; CREATE_PENDING remains NULL/0/viewer. */
    public Map<String,Object> commit(Auth.Session actor, Map<String,Object> reviewedDecisions) throws Exception {
        return importer.commit(actor, reviewedDecisions);
    }

    /**
     * Revalidates the complete durable receipt before returning independent account associations.
     * The integrating host must separately check current account state and configuration CAS.
     * Returned flags describe intake effects, not later independent administrator changes.
     */
    public Map<String,Object> received(Auth.Session actor) throws Exception {
        OrganizationAccountImport.SupplementalIntake intake = importer.verifiedSupplementalIntake(actor);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (OrganizationAccountImport.ReceivedAccount account : intake.accounts()) {
            rows.add(Map.of("reference", account.sourceReference(), "accountId", account.accountId(),
                    "personCode", account.personCode(), "accountEnabled", account.accountEnabled()));
        }
        return Map.of("batchKey", intake.batchKey(), "sourceFingerprint", intake.sourceFingerprint(),
                "intakeRevision", intake.intakeRevision(), "receiptFingerprint", intake.receiptFingerprint(),
                "rows", List.copyOf(rows), "permissionsPublished", false, "accountsActivated", false);
    }
}
