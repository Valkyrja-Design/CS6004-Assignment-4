import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

import soot.Body;
import soot.BodyTransformer;

public class AnalysisTransformer extends BodyTransformer {
    @Override
    protected void internalTransform(Body body, String phaseName, Map<String, String> options){
        if (body.getMethod().isConstructor())
            return;
        // write the unchanged shimple body to a file
        String fileName = body.getMethod().getName() + ".shimple";
        try {
            FileWriter fWriter = new FileWriter(fileName);
            fWriter.write(body.toString());
            fWriter.close();
        } catch (IOException e) {
            System.err.println("Couldn't open file '" + fileName + "'");
            e.printStackTrace();
        }

        // System.out.println("---------------------- " + body.getMethod().getName() + " ----------------------");

        new SimpleConstPropagator(body);
    }
}
