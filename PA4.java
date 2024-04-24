import soot.*;

public class PA4 {
    public static void main(String[] args) {
        final String defaultOptimization = "scp";
        if (args.length > 0 && (args[0] != "-o" || args.length != 2)){
            System.err.println("Usage: java <script> [-o [scp | sccp] ]");
            System.exit(1);
        }

        String optimization = defaultOptimization;
        if (args.length > 0)
            optimization = args[1];

        String classPath = "."; 	        // change to appropriate path to the test class
        String dir = "./testcases";

        //Set up arguments for Soot
        String[] sootArgs = {
            "-cp", classPath, "-pp",        // sets the class path for Soot
            "-keep-line-number",            // preserves line numbers in input Java files  
            "-f", "shimple",
            // "-via-shimple",
            "-main-class", "Test",	        // specify the main class
            // "Test", "Node"                  // list the classes to analyze
            "-process-dir", dir
        };

        // Create transformer for analysis
        AnalysisTransformer analysisTransformer = new AnalysisTransformer(optimization);

        // Add transformer to appropriate pack in PackManager; PackManager will run all packs when soot.Main.main is called
        PackManager.v().getPack("stp").add(new Transform("stp.cp", analysisTransformer));

        // Call Soot's main method with arguments
        soot.Main.main(sootArgs);
    }
}
