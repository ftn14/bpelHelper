package main;

public class Application {

    private static final String microflowPath = "path_to_module";

    public static void main(String[] args) {
        try {
            new BpelHelper(microflowPath).removeExcessPartners();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
