#!/usr/bin/env bash
# Compile and test with isolated synthetic databases. No production input.
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
# Never inherit a production collection into isolated tests or their HTTP servers.
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
cd "$(dirname "$0")/.."
python3 scripts/test_release_packaging.py
node scripts/check_frontend.cjs
node scripts/test_local_esm.cjs
node scripts/test_frontend.cjs
node scripts/IntegrationWorkflowUI.test.cjs
node scripts/IntegrationLimitedProjectUI.test.cjs
node scripts/IntegrationAccountBindingsUI.test.cjs
node scripts/IntegrationAccountPermissionsUI.test.cjs
node scripts/IntegrationAccountRelationshipsUI.test.cjs
node scripts/IntegrationAccountProvisioningUI.test.cjs
node scripts/IntegrationFormalSettlementUI.test.cjs
node scripts/IntegrationFormalCasesUI.test.cjs
node scripts/IntegrationFinancialReportsUI.test.cjs
node scripts/IntegrationSurveyFormalUI.test.cjs
node scripts/IntegrationSurveyFormalEntryUI.test.cjs
node scripts/IntegrationSummaryFormalUI.test.cjs
node scripts/IntegrationSummaryPhotosUI.test.cjs
node scripts/IntegrationSummaryCombinedUI.test.cjs
node scripts/S01AccountEmailMaintenanceUI.test.cjs
node scripts/IntegrationAccountImportUI.test.cjs
node scripts/IntegrationManagementSupplementUI.test.cjs
node scripts/IntegrationBranchCoverageUI.test.cjs
node scripts/IntegrationTeacherRosterUI.test.cjs
node scripts/IntegrationAccountEmailUI.test.cjs
node scripts/IntegrationLoginVerificationUI.test.cjs
node scripts/TrustedDeviceUI.test.cjs
node scripts/FirstBindUI.test.cjs
node scripts/M01-import-ui-check.mjs
node scripts/M01-account-import-preview-check.mjs
node scripts/M01-account-import-cli-check.mjs
node scripts/M01-approval-role-check.mjs
node scripts/M01-approval-role-cli-check.mjs
node scripts/IntegrationDeliveryCatalogUI.test.cjs
node scripts/IntegrationCourseMaintenanceUI.test.cjs
node scripts/IntegrationCertificationMaintenanceUI.test.cjs
node scripts/IntegrationDeliveryHistoryUI.test.cjs
node scripts/IntegrationPolicyPreviewUI.test.cjs
node scripts/IntegrationReportsUI.test.cjs
node scripts/IntegrationSurveyUI.test.cjs
node scripts/IntegrationSummariesUI.test.cjs
node scripts/IntegrationSummaryPreviewUI.test.cjs
node scripts/IntegrationNotificationsUI.test.cjs
node scripts/IntegrationRecommendationUi_test.mjs
node scripts/M04ReadonlyUi_test.mjs
node scripts/M05IntegrationUI.test.mjs
node scripts/test_regions.cjs
node scripts/test_login_book.cjs
node scripts/test_login_return.cjs
node scripts/test_teacher_upload.cjs
node scripts/test_teacher_draft_ui.cjs
node scripts/test_semantic_ui.cjs
node scripts/test_city_reference_ui.cjs
node scripts/test_city_planning_ui.cjs
node scripts/test_context_planning_ui.cjs
node scripts/test_city_presentation_ui.cjs
node scripts/test_city_presentation_v2_ui.cjs
node scripts/test_requirement_input_ui.cjs
node scripts/test_book_geometry.mjs
node scripts/test_book_binding.cjs
node scripts/test_book_headlines.cjs
node scripts/test_login_background.cjs
node scripts/check_business_art.cjs
node scripts/test_r7.cjs
node scripts/test_release_days.cjs
node scripts/test_environment.cjs
node scripts/test_public_popup_corridors.cjs
python3 semantic/test_worker.py
python3 semantic/test_requirement_content.py
python3 semantic/test_learner_artifact.py
python3 semantic/test_artifact_continuation.py
python3 semantic/test_evidence_sections.py
python3 semantic/test_evidence_facts.py
python3 semantic/test_capability_sentence.py
python3 semantic/test_evidence_role_context.py
python3 semantic/test_evidence_grammar.py
python3 semantic/test_evidence_actor_event.py
python3 semantic/test_server_event_scope.py
python3 semantic/test_narrative_entity_evidence.py
python3 semantic/test_leading_profile_scope.py
python3 semantic/test_mixed_scope.py
python3 semantic/test_posttrain_v3_data.py
python3 semantic/test_posttrain_v3_pipeline_validation.py
python3 semantic/test_posttrain_v4_data.py
python3 semantic/test_posttrain_v4_metrics.py
python3 semantic/test_posttrain_v4_audit.py
check_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-check.XXXXXX")"
check_pid=""
check_java="${TRAINING_CHECK_JAVA:-java}"
M01_IMPORT_JAVA="$check_java" bash scripts/M01-import-check.sh
M01_IMPORT_JAVA="$check_java" bash scripts/M01-import-host-check.sh
cleanup() {
  if [[ -n "$check_pid" ]] && kill -0 "$check_pid" 2>/dev/null; then
    kill "$check_pid" 2>/dev/null || true
    wait "$check_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
mkdir -p "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 \
  -cp 'lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar' -d "$check_root/out" src/com/training/*.java
TRUSTED_DEVICE_JAVA="$check_java" TRUSTED_DEVICE_PRODUCT_CLASSES="$check_root/out" bash scripts/TrustedDevices-check.sh
FIRST_BIND_JAVA="$check_java" FIRST_BIND_PRODUCT_CLASSES="$check_root/out" bash scripts/FirstBind-check.sh
# Verify identity/workflow adapters against fresh stores and real localhost routes.
# Formal financial, survey and summary chains use independent synthetic databases.
bash scripts/check_release_extensions.sh --java "$check_java" --classes "$check_root/out"
bash scripts/check_summary_combined.sh --java "$check_java" --classes "$check_root/out"
bash scripts/check_management_supplement.sh --java "$check_java" --classes "$check_root/out"
bash scripts/check_management_supplement_http.sh --java "$check_java" --classes "$check_root/out"
bash scripts/check_management_group.sh --java "$check_java" --classes "$check_root/out"
bash scripts/check_settlement_coding.sh --java "$check_java" --classes "$check_root/out"
M05_FORMAL_JAVA="$check_java" bash scripts/M05FormalWorkflow-check.sh
M06_SETTLEMENT_JAVA="$check_java" bash scripts/M06Settlement-check.sh
M06_SETTLEMENT_JAVA="$check_java" bash scripts/M06SettlementBridge-check.sh
mkdir -p "$check_root/formal-settlement-test-out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/formal-settlement-test-out" scripts/IntegrationFormalSettlementHttpFixture.java
node scripts/IntegrationFormalSettlementHttp.cjs --java "$check_java" --classes "$check_root/formal-settlement-test-out" --product-classes "$check_root/out"
M07_FORMAL_JAVA="$check_java" bash scripts/M07Formal-check.sh
M08_JAVA="$check_java" M08_WORD_QA_DIR="$check_root/formal-word-qa" bash scripts/M08Formal-check.sh
M08_JAVA="$check_java" bash scripts/M08ReviewedSurvey-check.sh
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M01AccountAccessInspectionTest.java scripts/IntegrationAccountPermissionsHttpFixture.java
inspection_check_root="$(mktemp -d "$check_root/yanxu-m01-access-inspection.XXXXXX")"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M01AccountAccessInspectionTest "$inspection_check_root"
node scripts/IntegrationAccountPermissionsHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M01AccountRelationshipsTest.java scripts/IntegrationAccountRelationshipsHttpFixture.java
relationships_check_root="$(mktemp -d "$check_root/yanxu-m01-account-relationships.XXXXXX")"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M01AccountRelationshipsTest "$relationships_check_root"
node scripts/IntegrationAccountRelationshipsHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/OrganizationAccountImportSourceTest.java scripts/M01AccountProvisioningTest.java scripts/IntegrationAccountProvisioningHttpFixture.java
provisioning_check_root="$(mktemp -d "$check_root/yanxu-m01-account-provisioning.XXXXXX")"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M01AccountProvisioningTest "$provisioning_check_root"
node scripts/IntegrationAccountProvisioningHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M01OrganizationDisplayNameTest.java
display_name_check_root="$(mktemp -d "$check_root/yanxu-m01-organization-display-name.XXXXXX")"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M01OrganizationDisplayNameTest "$display_name_check_root"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/OrganizationAccountImportSourceTest.java scripts/M01BranchCoverageTest.java scripts/IntegrationBranchCoverageHttpFixture.java
coverage_check_root="$(mktemp -d "$check_root/yanxu-m01-branch-coverage.XXXXXX")"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M01BranchCoverageTest "$coverage_check_root"
node scripts/IntegrationBranchCoverageHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M04TeacherRosterImportTest.java scripts/IntegrationTeacherRosterHttpFixture.java
mkdir -p "$check_root/teacher-roster-data" "$check_root/teacher-roster-sources"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M04TeacherRosterImportTest "$check_root/teacher-roster-data" "$check_root/teacher-roster-sources"
node scripts/IntegrationTeacherRosterHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M04CourseMaintenanceTest.java scripts/IntegrationCourseMaintenanceHttpFixture.java
mkdir -p "$check_root/course-maintenance-data"
"$check_java" -Ddata.dir="$check_root/course-maintenance-data" -cp "$check_root/out:lib/h2.jar" com.training.M04CourseMaintenanceTest "$check_root/course-maintenance-data"
node scripts/IntegrationCourseMaintenanceHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M04CertificationMaintenanceTest.java scripts/IntegrationCertificationMaintenanceHttpFixture.java
mkdir -p "$check_root/certification-maintenance-data"
"$check_java" -Ddata.dir="$check_root/certification-maintenance-data" -cp "$check_root/out:lib/h2.jar" com.training.M04CertificationMaintenanceTest "$check_root/certification-maintenance-data"
node scripts/IntegrationCertificationMaintenanceHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/IntegrationApiRequestIsolationTest.java
mkdir -p "$check_root/request-isolation-data"
"$check_java" -Ddata.dir="$check_root/request-isolation-data" -cp "$check_root/out:lib/h2.jar" com.training.IntegrationApiRequestIsolationTest "$check_root/request-isolation-data"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/IntegrationIdentityTest.java scripts/IntegrationWorkflowTest.java scripts/IntegrationAccountCatalogHttpFixture.java scripts/IntegrationDeliveryHttpFixture.java scripts/IntegrationReportsHttpFixture.java scripts/M06IntegrationTest.java scripts/IntegrationSurveyHttpFixture.java scripts/M07IntegrationTest.java scripts/M07ResponseImportsTest.java scripts/IntegrationSummariesHttpFixture.java scripts/M08IntegrationTest.java scripts/IntegrationSessionRevocationTest.java scripts/IntegrationNotificationsHttpFixture.java
mkdir -p "$check_root/identity-data" "$check_root/workflow-data" "$check_root/reports-data" "$check_root/survey-data" "$check_root/summaries-data" "$check_root/session-revocation-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.IntegrationIdentityTest "$check_root/identity-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.IntegrationWorkflowTest "$check_root/workflow-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.IntegrationSessionRevocationTest "$check_root/session-revocation-data"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/IntegrationRoleScopesTest.java scripts/IntegrationPolicyPreviewHttpFixture.java
mkdir -p "$check_root/role-scopes-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.IntegrationRoleScopesTest "$check_root/role-scopes-data"
node scripts/IntegrationPolicyPreviewHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/IntegrationCombinedWorkflowTest.java
mkdir -p "$check_root/yanxu-combined-data-check"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.IntegrationCombinedWorkflowTest "$check_root/yanxu-combined-data-check"
node scripts/IntegrationCombinedWorkflowHttp.cjs --java "$check_java" --classes "$check_root/out"
S01_INTEGRATION_JAVA="$check_java" bash scripts/S01Integration-check.sh
M04_INTEGRATION_JAVA="$check_java" bash scripts/M04RecommendationQualification_check.sh
node scripts/IntegrationNotificationsHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -nowarn -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/IntegrationRecommendationHttpFixture.java scripts/RecommendationQualifiedJobsTest.java
mkdir -p "$check_root/recommendation-jobs-data"
"$check_java" -cp "$check_root/out:lib/*" com.training.RecommendationQualifiedJobsTest "$check_root/recommendation-jobs-data"
node scripts/IntegrationRecommendationHttp.cjs --java "$check_java" --classes "$check_root/out"
node scripts/IntegrationWorkflowHttp.cjs --java "$check_java" --classes "$check_root/out"
node scripts/IntegrationAccountCatalogHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/S01AccountEmailPreparationTest.java scripts/IntegrationAccountEmailHttpFixture.java
mkdir -p "$check_root/account-email-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.S01AccountEmailPreparationTest "$check_root/account-email-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.S01AccountEmailPreparationTest --reopen "$check_root/account-email-data"
node scripts/IntegrationAccountEmailHttp.cjs --java "$check_java" --classes "$check_root/out"
S01_LOGIN_JAVA="$check_java" bash scripts/S01LoginVerification-check.sh
S01_LOGIN_JAVA="$check_java" bash scripts/S01LoginVerificationMailAdapter-check.sh
S01_MAINTENANCE_JAVA="$check_java" bash scripts/S01AccountEmailMaintenance-check.sh
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/IntegrationLoginVerificationHostTest.java scripts/IntegrationLoginVerificationHttpFixture.java
mkdir -p "$check_root/login-verification-host-data"
"$check_java" -Ddata.dir="$check_root/login-verification-host-data" -cp "$check_root/out:lib/h2.jar" com.training.IntegrationLoginVerificationHostTest "$check_root/login-verification-host-data"
node scripts/IntegrationLoginVerificationHttp.cjs --java "$check_java" --classes "$check_root/out"
node scripts/IntegrationDeliveryHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M05DeliveryHistoryTest.java scripts/IntegrationDeliveryHistoryHttpFixture.java
mkdir -p "$check_root/delivery-history-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M05DeliveryHistoryTest "$check_root/delivery-history-data"
node scripts/IntegrationDeliveryHistoryHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M06IntegrationTest "$check_root/reports-data"
node scripts/IntegrationReportsHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M06TeachingExportTest.java scripts/IntegrationTeachingExportHttpFixture.java
mkdir -p "$check_root/teaching-export-data"
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.M06TeachingExportTest "$check_root/teaching-export-data"
node scripts/IntegrationTeachingExportHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -Xmx512m -cp "$check_root/out:lib/h2.jar" com.training.M07ResponseImportsTest
"$check_java" -Xmx512m -cp "$check_root/out:lib/h2.jar" com.training.M07IntegrationTest "$check_root/survey-data"
node scripts/IntegrationSurveyHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -Xmx256m -cp "$check_root/out:lib/h2.jar" com.training.M08IntegrationTest "$check_root/summaries-data"
node scripts/IntegrationSummariesHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M08DeliverySourceTest.java scripts/IntegrationSummaryDeliveryHttpFixture.java
mkdir -p "$check_root/summary-delivery-data"
"$check_java" -Xmx256m -cp "$check_root/out:lib/h2.jar" com.training.M08DeliverySourceTest "$check_root/summary-delivery-data" scripts
node scripts/IntegrationSummaryDeliveryHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar" -d "$check_root/out" scripts/M08ComparisonTest.java scripts/IntegrationSummaryComparisonHttpFixture.java
mkdir -p "$check_root/summary-comparison-data"
"$check_java" -Xmx256m -cp "$check_root/out:lib/h2.jar" com.training.M08ComparisonTest "$check_root/summary-comparison-data"
node scripts/IntegrationSummaryComparisonHttp.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" -d "$check_root/out" scripts/PosttrainingV2Probe.java scripts/PostposedExclusionTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar" com.training.PostposedExclusionTest
ACTOR_TEST_JAVA="$check_java" ACTOR_TEST_CLASSPATH="$check_root/out:lib/h2.jar" python3 semantic/test_postposed_exclusion.py
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/InstructorModeTest.java
"$check_java" -cp "$check_root/out" com.training.InstructorModeTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/NamedPassiveHistoryTest.java
"$check_java" -cp "$check_root/out" com.training.NamedPassiveHistoryTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/CapabilitySentenceTest.java
"$check_java" -cp "$check_root/out" com.training.CapabilitySentenceTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/MethodContrastTest.java
"$check_java" -cp "$check_root/out" com.training.MethodContrastTest
ACTOR_TEST_JAVA="$check_java" ACTOR_TEST_CLASSPATH="$check_root/out:lib/h2.jar" python3 semantic/test_method_contrast.py
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/LearnerArtifactTest.java
"$check_java" -cp "$check_root/out" com.training.LearnerArtifactTest
ACTOR_TEST_JAVA="$check_java" ACTOR_TEST_CLASSPATH="$check_root/out:lib/h2.jar" python3 semantic/test_learner_artifact.py
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/PhysicalContinuationTest.java
"$check_java" -cp "$check_root/out" com.training.PhysicalContinuationTest
ACTOR_TEST_JAVA="$check_java" ACTOR_TEST_CLASSPATH="$check_root/out:lib/h2.jar" python3 semantic/test_artifact_continuation.py
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/EventModeBindingTest.java
"$check_java" -cp "$check_root/out" com.training.EventModeBindingTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/PassiveRecipientTest.java
"$check_java" -cp "$check_root/out" com.training.PassiveRecipientTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/HistoricalRequestTest.java
"$check_java" -cp "$check_root/out" com.training.HistoricalRequestTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/OnsiteHistoryBindingTest.java
"$check_java" -cp "$check_root/out" com.training.OnsiteHistoryBindingTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/RelativeHistoricalRequestTest.java
"$check_java" -cp "$check_root/out" com.training.RelativeHistoricalRequestTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/ActiveTeachingFieldsTest.java
"$check_java" -cp "$check_root/out" com.training.ActiveTeachingFieldsTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/DeliveryModeWordingTest.java
"$check_java" -cp "$check_root/out" com.training.DeliveryModeWordingTest
node scripts/test_teaching_event_protocol.cjs "$check_java" "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar"
python3 -B scripts/test_posttraining_audit.py --java "$check_java" \
  --classpath "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" \
  --standalone --output "$check_root/posttraining-audit.json"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/PosttrainingMixedProtocolTest.java
"$check_java" -cp "$check_root/out" com.training.PosttrainingMixedProtocolTest >"$check_root/posttraining-mixed-protocol.json"
ACTOR_TEST_JAVA="$check_java" ACTOR_TEST_CLASSPATH="$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" python3 semantic/test_evidence_actor_protocol.py
ACTOR_TEST_JAVA="$check_java" ACTOR_TEST_CLASSPATH="$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" python3 semantic/test_local_course_denial_protocol.py
ACTOR_TEST_JAVA="$check_java" ACTOR_TEST_CLASSPATH="$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" python3 semantic/test_personal_course_offer_protocol.py
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/DispatchPreferenceTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.DispatchPreferenceTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/LocalSemanticTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.LocalSemanticTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/TeacherDraftTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.TeacherDraftTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/EvidenceContextTest.java
"$check_java" -cp "$check_root/out" com.training.EvidenceContextTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/EvidenceAdversarialTest.java
"$check_java" -cp "$check_root/out" com.training.EvidenceAdversarialTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/EvidenceScopeTest.java
"$check_java" -cp "$check_root/out" com.training.EvidenceScopeTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/EvidencePartitionTest.java
"$check_java" -cp "$check_root/out" com.training.EvidencePartitionTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/LocalCourseDenialTest.java
"$check_java" -cp "$check_root/out" com.training.LocalCourseDenialTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/PersonalCourseOfferTest.java
"$check_java" -cp "$check_root/out" com.training.PersonalCourseOfferTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/PrepareStationCityReview.java scripts/PrepareStationCityReviewTest.java
"$check_java" -cp "$check_root/out" com.training.PrepareStationCityReviewTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/PrepareMappedCityCorridors.java scripts/PrepareMappedCityCorridorsTest.java
"$check_java" -cp "$check_root/out" com.training.PrepareMappedCityCorridorsTest
for evidence_test in EvidenceFactsTest EvidenceRoleContextTest EvidenceGrammarTest EvidenceActorEventTest LeadingProfileScopeTest MixedEvidenceScopeTest RequirementObjectGrammarTest FocusedQuerySchemaTest RequirementAtomicTest QualificationSourceClaimTest RequirementAudienceScopeTest RequirementNumericActionTest RequirementGenericTopicTest RequirementContractTest RequirementInputTest RequirementInputResolutionTest RequirementTopicTest RequirementTopicIntegrationTest RequirementNarrativeTest AudienceContractTest AudienceIntentTest AudienceNamedSubjectTest FutureActivityAudienceTest CompleteAudienceHistoryTest CoordinatedAudienceHistoryTest TeachingEventBindingTest NarrativeTeachingEventsTest NarrativeSafetyTest NarrativeEntityBindingTest CityPlanningSelectionTest CuratedCityDurationTest CuratedPlanningIntegrationTest PublicUiNamedStationTest PublicUiStopTableTest PublicUiDateBasisTest PublicUiPopupTableTest CuratedClockNotationTest; do
  "$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" "scripts/$evidence_test.java"
  if [[ "$evidence_test" == "PublicUiDateBasisTest" ]]; then
    "$check_java" -cp "$check_root/out" "com.training.$evidence_test" "$check_root/ui-date-basis"
    node scripts/test_ui_sample_dates.cjs "$check_root/ui-date-basis/renderable-samples.json"
  elif [[ "$evidence_test" == "PublicUiPopupTableTest" ]]; then
    "$check_java" -cp "$check_root/out" "com.training.$evidence_test" "$check_root/ui-popup-table"
    node scripts/test_ui_sample_dates.cjs "$check_root/ui-popup-table/renderable-samples.json"
  else
    "$check_java" -cp "$check_root/out" "com.training.$evidence_test"
  fi
done
for notation_test in CuratedHalfHourTest CuratedSubcentreTest; do
  "$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" "scripts/$notation_test.java"
  "$check_java" -cp "$check_root/out" "com.training.$notation_test"
done
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/CityPlanningPresentationTest.java
"$check_java" -cp "$check_root/out" com.training.CityPlanningPresentationTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/CityPlanningPresentationV2Test.java
"$check_java" -cp "$check_root/out" com.training.CityPlanningPresentationV2Test
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" \
  scripts/MergeCityPlanningReferences.java scripts/MergeCityPlanningReferencesTest.java \
  scripts/PrepareCityPlanningCollection.java scripts/CityPlanningCollectionTest.java
"$check_java" -cp "$check_root/out" com.training.MergeCityPlanningReferencesTest
"$check_java" -cp "$check_root/out" com.training.CityPlanningCollectionTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/SharedCityDurationTest.java scripts/SharedCityPlanningSelectionTest.java
"$check_java" -cp "$check_root/out" com.training.SharedCityDurationTest
"$check_java" -cp "$check_root/out" com.training.SharedCityPlanningSelectionTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out" -d "$check_root/out" scripts/TeacherUploadLimitTest.java
for upload_limits in default oversized lowered invalid; do
  "$check_java" -cp "$check_root/out" com.training.TeacherUploadLimitTest "$upload_limits"
done
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/NearbySelectionTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.NearbySelectionTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/RailTimetableTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.RailTimetableTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/TravelMatrixTest.java
"$check_java" -Ddispatch.transport.file= -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.TravelMatrixTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/TransportReferencePolicyTest.java
"$check_java" -Ddispatch.timetable.file= -Ddispatch.transport.file= -Ddispatch.transport.dir= -Ddispatch.routes.file= -Ddispatch.organizations.file= -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.TransportReferencePolicyTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/CityTravelReferenceTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.CityTravelReferenceTest
for public_test in PublicTransportEvidenceTest PublicReferenceResolutionTest PublicCityReferencePrepare PublicScheduleBaselineTest PublicCityBoundsTest PublicCityBoundsIntegrationTest PublicCityContextTest PublicRailJourneyContextTest PublicStopChainTest PublicHeadedEndpointTest; do
  "$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" "scripts/$public_test.java"
done
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicTransportEvidenceTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicScheduleBaselineTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicCityBoundsTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicCityBoundsIntegrationTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicCityContextTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicRailJourneyContextTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicStopChainTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicHeadedEndpointTest
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.PublicReferenceResolutionTest
# The original test_requirement_input_http.cjs is retained as the before-contract
# baseline. This run checks the migrated 55 assertions, not the historical 55.
node scripts/test_requirement_input_resolution_http.cjs --java "$check_java" --classes "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/ip2region-3.3.7.jar" -d "$check_root/out" scripts/WeatherTest.java
"$check_java" -cp "$check_root/out:lib/ip2region-3.3.7.jar" com.training.WeatherTest
for check_file in web/app.js web/materials.js test.js test_integrity.js test_faculty.js; do
  node --check "$check_file"
done
run_suite() {
  local suite="$1" ready=0 port
  # Let the OS allocate a loopback port; never reuse someone else's preview.
  port="$(node -e 'const s=require("node:net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')"
  # Refuse occupied ports instead of accidentally testing someone else's instance.
  node -e 'const n=require("node:net"),s=n.createServer();s.on("error",()=>{console.error("Test port already in use");process.exit(1)});s.listen(Number(process.argv[1]),"127.0.0.1",()=>s.close())' "$port"
  mkdir -p "$check_root/${suite%.js}"
  "$check_java" -Dfile.encoding=UTF-8 -Dbootstrap.demo=true -Dbind.address=127.0.0.1 \
    -Ddispatch.timetable.file= \
    -Ddispatch.transport.file= \
    -Ddispatch.city.references.file= \
    -Ddispatch.city.references.dir= \
    -Ddispatch.public.city.references.file= \
    -Ddispatch.public.city.references.dir= \
    -Ddispatch.city.planning.references.file= \
    -Ddispatch.city.planning.collection.file= \
    -Ddata.dir="$check_root/${suite%.js}" \
    -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" com.training.Main "$port" \
    >"$check_root/${suite%.js}.log" 2>&1 &
  check_pid=$!
  for ((attempt = 0; attempt < 80; attempt++)); do
    if ! kill -0 "$check_pid" 2>/dev/null; then break; fi
    if curl --fail --silent "http://127.0.0.1:$port/" >/dev/null; then ready=1; break; fi
    sleep 0.25
  done
  if [[ "$ready" != 1 ]]; then
    tail -n 40 "$check_root/${suite%.js}.log"
    return 1
  fi
  if [[ "$suite" = test.js ]]; then
    node scripts/check_frontend.cjs --base-url "http://127.0.0.1:$port"
  fi
  TRAINING_API_BASE="http://127.0.0.1:$port/api" node "$suite"
  kill "$check_pid"
  wait "$check_pid" 2>/dev/null || true
  check_pid=""
}
run_suite test.js
run_suite test_integrity.js
run_suite test_faculty.js
printf 'All regression suites passed. Isolated logs: %s\n' "$check_root"
