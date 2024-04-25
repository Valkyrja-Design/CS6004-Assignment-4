import soot.*;

public class PA4 {
    public static void main(String[] args) {
        String classPath = "."; 	        // change to appropriate path to the test class
        String dir = "./testcases";

        //Set up arguments for Soot
        String[] sootArgs = {
            "-cp", classPath, "-pp",        // sets the class path for Soot
            "-keep-line-number",            // preserves line numbers in input Java files  
            "-f", "c",
            "-via-shimple",
            "-p", "jop.cpf",
            "enabled:false",
            "-main-class", "Test",	        // specify the main class
            "-process-dir", dir
        };

        // Create transformer for analysis
        AnalysisTransformer analysisTransformer = new AnalysisTransformer();

        // Add transformer to appropriate pack in PackManager; PackManager will run all packs when soot.Main.main is called
        PackManager.v().getPack("stp").add(new Transform("stp.cp", analysisTransformer));

        // Call Soot's main method with arguments
        soot.Main.main(sootArgs);
    }
}
