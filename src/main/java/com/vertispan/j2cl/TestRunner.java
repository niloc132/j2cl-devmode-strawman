package com.vertispan.j2cl;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.FluentWait;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

public class TestRunner {

    public static void main(String[] args) throws InterruptedException, ExecutionException, IOException {
        Gwt3TestOptions options = new Gwt3TestOptions();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            e.printStackTrace();
            parser.printUsage(System.err);
            System.exit(1);
        }

        Gwt3Options compileOptions = options.makeOptions();
        SingleCompiler.run(compileOptions);

        // write a simple html file to that output dir
        compileOptions.getJsOutputFile();
        new File(compileOptions.getJsOutputFile()).getParent();
        Path junitStartupFile = Paths.get(new File(compileOptions.getJsOutputFile()).getParent(), "junit.html");
        Files.copy(TestRunner.class.getResourceAsStream("/junit.html"), junitStartupFile, StandardCopyOption.REPLACE_EXISTING);
        String startupHtmlFile = junitStartupFile.toAbsolutePath().toString();


        // assuming that was successful, start htmlunit to run the test
        WebDriver driver = null;
        try {
            driver = new ChromeDriver();
            driver.get("file://" + startupHtmlFile);

            // loop and poll if tests are done
            new FluentWait<>(driver)
                    .withTimeout(Duration.ofMinutes(1))
                    .withMessage("Tests failed to finish in timeout")
                    .pollingEvery(Duration.ofMillis(100))
                    .until(d -> isFinished(d));

            // check for success
            if (!isSuccess(driver)) {
                System.err.println("At least one test failed, please try manually");
            } else {
                System.err.println("Tests passed!");
            }
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }

    }

    private static boolean isSuccess(WebDriver d) {
        return (Boolean) ((JavascriptExecutor) d).executeScript("return window.G_testRunner.isSuccess()");
    }

    private static boolean isFinished(WebDriver d) {
        return (Boolean) ((JavascriptExecutor) d).executeScript("return !!(window.G_testRunner && window.G_testRunner.isFinished())");
    }
}
