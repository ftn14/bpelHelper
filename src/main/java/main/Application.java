package main;

public class Application {

    private static final String microFlowPath = "path_to_module";

    public static void main(String[] args) {
        try {
            new BpelHelper(microFlowPath).removeExcessPartners();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
