import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class DependencyExtractor {

    private static final ConcurrentHashMap<String, ClassInfo> resultMap = new ConcurrentHashMap<>();
    private static final String LOG_FILE = "javap_output.log";

    public static void main(String[] args) throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis(); //start time

        String folderPath = "/to/your/folder/path";  // Update this path

        // Use a ForkJoinPool for efficient work-stealing
        ForkJoinPool forkJoinPool = new ForkJoinPool();

        try (PrintWriter logWriter = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE, true)))) {

            // Gather tasks to process .class files
            List<Callable<Void>> tasks = new ArrayList<>();
            Files.walk(Paths.get(folderPath))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> {
                        tasks.add(() -> {
                            System.out.println("Thread " + Thread.currentThread().getName() + " processing: " + path.getFileName());
                            try {
                                String className = path.toString()
                                        .replace(folderPath + File.separator, "")
                                        .replace(File.separator, ".")
                                        .replace(".class", "");
                                ClassInfo classInfo = extractClassInfo(path, logWriter);
                                resultMap.put(className, classInfo);
                            } catch (Exception e) {
                                System.err.println("Error processing file: " + path.toString());
                                e.printStackTrace();
                            }
                            return null;
                        });
                    });

            // Invoke all tasks in parallel using the ForkJoinPool
            forkJoinPool.invokeAll(tasks);
        }

        // Output results to JSON
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter("class_info.json")) {
            gson.toJson(resultMap, writer);
        }
        long endTime = System.currentTimeMillis(); //end time
        long duration = endTime - startTime; // total time took for this program

        System.out.println("Class information saved to class_info.json");
        System.out.println("javap output logged in " + LOG_FILE);
        System.out.println("Time taken: " + (duration / 1000.0) + " seconds");
    }

    private static ClassInfo extractClassInfo(Path classFilePath, PrintWriter logWriter) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("javap -v " + classFilePath.toAbsolutePath());
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        // Precompiled regex patterns for efficiency
        Pattern classPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z\\d_$]*(?:\\.[a-zA-Z_$][a-zA-Z\\d_$]*)+)");
        Pattern methodPattern = Pattern.compile("\\s*(public|protected|private|static|final|native|synchronized|abstract|transient|volatile)*\\s+([\\w<>\\[\\]]+)\\s+([\\w]+)\\(.*\\);");
        Pattern fieldPattern = Pattern.compile("\\s*(public|protected|private|static|final|transient|volatile)*\\s+([\\w<>\\[\\]]+)\\s+([\\w]+);");

        Set<String> dependencies = new HashSet<>();
        List<String> methods = new ArrayList<>();
        List<String> dataMembers = new ArrayList<>();

        String line;
        logWriter.println("Class: " + classFilePath.getFileName());
        while ((line = reader.readLine()) != null) {
            logWriter.println(line);

            // Extract dependencies
            Matcher classMatcher = classPattern.matcher(line);
            while (classMatcher.find()) {
                String dependency = classMatcher.group(1);
                dependencies.add(dependency);
            }

            // Extract methods
            Matcher methodMatcher = methodPattern.matcher(line);
            if (methodMatcher.find()) {
                String methodName = methodMatcher.group(3);
                methods.add(methodName);
            }

            // Extract fields
            Matcher fieldMatcher = fieldPattern.matcher(line);
            if (fieldMatcher.find()) {
                String fieldName = fieldMatcher.group(3);
                dataMembers.add(fieldName);
            }
        }

        process.waitFor();
        logWriter.flush();

        return new ClassInfo(methods, dataMembers, dependencies);
    }

    // Class to hold class details
    private static class ClassInfo {
        List<String> methods;
        List<String> dataMembers;
        Set<String> dependencies;

        ClassInfo(List<String> methods, List<String> dataMembers, Set<String> dependencies) {
            this.methods = methods;
            this.dataMembers = dataMembers;
            this.dependencies = dependencies;
        }
    }
}
