package wtf.thepalbi;

import org.apache.commons.io.output.FileWriterWithEncoding;
import soot.Body;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import wtf.thepalbi.relations.FactWriter;
import wtf.thepalbi.relations.LookupFact;
import wtf.thepalbi.utils.UUIDHeapLocationFactory;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static wtf.thepalbi.relations.FactWriter.writeMethod;
import static wtf.thepalbi.relations.FactWriter.writeSignature;

public class PointToAnalysis {

    public static String IO_SEPARATOR = ";";

    private Map<String, FileWriter> factTypeToFile = new HashMap<>();

    public void main(Body methodBody, Scene scene) throws Exception {
        Path workingDirectory = Files.createTempDirectory("points-to-");
        Path inputDirectory = Files.createDirectory(Paths.get(workingDirectory + "/input"));
        Path outputDirectory = Files.createDirectory(Paths.get(workingDirectory + "/output"));
        Collection<SouffleFact> collectedFacts = this.collectFactsFromMethodBody(methodBody);

        // Generate Subtype facts
        Collection<SouffleFact> lookupFacts = new HashSet<>();

        collectedFacts.stream()
                .filter(fact -> fact instanceof TypeFact)
                .map(fact -> ((TypeFact) fact).getType())
                .forEach(seenTypeName -> {
                    SootClass seenClass = scene.getSootClass(seenTypeName);
                    for (SootMethod method : seenClass.getMethods()) {
                        // Generate a Lookup fact for seenType -> method -> method signature
                        lookupFacts.add(new LookupFact(
                                seenTypeName,
                                writeSignature(method),
                                writeMethod(method)));
                    }
                });

        collectedFacts.addAll(lookupFacts);

        // Write all facts to their corresponding input files
        for (SouffleFact fact : collectedFacts) {
            if (!this.factTypeToFile.containsKey(fact.getRelationName())) {
                Path factsFile = Paths.get(inputDirectory + "/" + fact.getRelationName() + ".facts");
                this.factTypeToFile.put(fact.getRelationName(), new FileWriter(factsFile.toFile()));
            }
            FileWriter currentWriter = this.factTypeToFile.get(fact.getRelationName());
            currentWriter.write(fact.toIODirective());
        }

        // Close all writers
        for (FileWriter writer : this.factTypeToFile.values()) {
            writer.close();
        }

        // Run Souffle script
        Process souffleProcess = Runtime.getRuntime().exec(new String[]{
                "souffle",
                "-F" + inputDirectory,
                "-D" + outputDirectory,
                this.getClass().getClassLoader().getResource("vanilla-andersen.dl").getPath()
        });

        int exitCode = souffleProcess.waitFor();
        if (exitCode != 0) {
            // Something failed in the Souffle process
            throw new Exception("Souffle process failed");
        }

        System.out.println(outputDirectory);
    }

    public Collection<SouffleFact> collectFactsFromMethodBody(Body body) {
        return new StmtToSouffleFactTranslator(new UUIDHeapLocationFactory()).translateMethodBody(body);
    }
}
