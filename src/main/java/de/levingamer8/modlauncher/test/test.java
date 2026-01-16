package de.levingamer8.modlauncher.test;


import java.nio.file.Path;

public class test {

    public static void main(String[] args) {

//        ForgeInstallService service = new ForgeInstallService();
//
//        try {
//            String forgeProfile = service.installForge(
//                    Path.of(System.getenv("APPDATA"), ".modlauncher").resolve("shared"),
//                    Path.of(System.getenv("APPDATA"), ".modlauncher").resolve("shared").resolve("installer-cache"),
//                    "1.20.1",
//                    "1.20.1-47.4.10",
//                    System.out::println
//            );
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }



        ForgeGameLauncher launcher = new ForgeGameLauncher();

        try {
            new ForgeGameLauncher().launch(
                    Path.of(System.getenv("APPDATA"), ".modlauncher").resolve("shared"),
                    "1.20.1-forge-47.4.10",
                    System.out::println
            );

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}