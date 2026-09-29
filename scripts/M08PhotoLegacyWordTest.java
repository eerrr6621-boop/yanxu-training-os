package com.training;
import java.nio.file.*;
import java.util.*;
import static com.training.M08FormalTest.*;
/** Old source renderer is renamed only in this isolated test compile, never installed. */
public final class M08PhotoLegacyWordTest {
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]).toAbsolutePath().normalize();try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new AssertionError("fresh synthetic DB only");}
        try{fixture(data);TrainingSummariesFeedbackSource.connect(new TrainingSummariesFeedbackSource.ReviewedProvider(){public Map<String,Object> capture(Auth.Session session,long project,String org){return feedback(project,org);}public void validate(Object value)throws Exception{var copy=new LinkedHashMap<>(m(value));Object hash=copy.remove("source_version");if(!Objects.equals(hash,TrainingSummariesWorkflow.hash(c(copy))))throw new Api.ApiException(409,"synthetic feedback invalid");}});
            System.setProperty("training.summary.review.order","BRANCH_THEN_BP");System.setProperty("training.summary.review.policyVersion","SYNTHETIC-OLD-WORD");save(2,101,"旧版无照片总结兼容");TrainingSummariesWorkflow.mutate("submit",s(2),body(101));review(5,101,"BRANCH","APPROVE");review(6,101,"BP","APPROVE");
            synchronized(Api.MUTATION_LOCK){var snapshot=TrainingSummariesIntegration.workflowSnapshot(s(7),101);var flow=TrainingSummariesWorkflow.describe(s(7),snapshot);check(Arrays.equals(TrainingSummariesLegacyWord.render(snapshot,flow),TrainingSummariesWord.render(snapshot,flow)),"unchanged exact bytes for existing text-only Word, preserving export replay hash");}
            System.out.println("M08PhotoLegacyWord: identical no-photo DOCX bytes with the frozen previous renderer.");
        }finally{System.clearProperty("training.summary.review.order");System.clearProperty("training.summary.review.policyVersion");Db.get().close();}
    }
}
