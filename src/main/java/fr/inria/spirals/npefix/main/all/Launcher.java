package fr.inria.spirals.npefix.main.all;

import fr.inria.spirals.npefix.config.Config;
import fr.inria.spirals.npefix.main.DecisionServer;
import fr.inria.spirals.npefix.main.ExecutionClient;
import fr.inria.spirals.npefix.resi.CallChecker;
import fr.inria.spirals.npefix.resi.context.Lapse;
import fr.inria.spirals.npefix.resi.context.NPEOutput;
import fr.inria.spirals.npefix.resi.oracle.ExceptionOracle;
import fr.inria.spirals.npefix.resi.oracle.TestOracle;
import fr.inria.spirals.npefix.resi.selector.DomSelector;
import fr.inria.spirals.npefix.resi.selector.RandomSelector;
import fr.inria.spirals.npefix.resi.selector.Selector;
import fr.inria.spirals.npefix.resi.strategies.Strategy;
import fr.inria.spirals.npefix.transformer.processors.AddImplicitCastChecker;
import fr.inria.spirals.npefix.transformer.processors.BeforeDerefAdder;
import fr.inria.spirals.npefix.transformer.processors.CheckNotNull;
import fr.inria.spirals.npefix.transformer.processors.ConstructorEncapsulation;
import fr.inria.spirals.npefix.transformer.processors.ForceNullInit;
import fr.inria.spirals.npefix.transformer.processors.MethodEncapsulation;
import fr.inria.spirals.npefix.transformer.processors.TargetModifier;
import fr.inria.spirals.npefix.transformer.processors.TernarySplitter;
import fr.inria.spirals.npefix.transformer.processors.TryRegister;
import fr.inria.spirals.npefix.transformer.processors.VarRetrieveAssign;
import fr.inria.spirals.npefix.transformer.processors.VarRetrieveInit;
import fr.inria.spirals.npefix.transformer.processors.VariableFor;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.SpoonException;
import spoon.SpoonModelBuilder;
import spoon.processing.ProcessingManager;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.AnnotationFilter;
import spoon.support.QueueProcessingManager;
import utils.TestClassesFinder;
import utils.sacha.runner.main.TestRunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Launcher {

    private final String[] sourcePath;
    private final String classpath;
    private final String sourceOutput;
    private final String binOutput;
    private final SpoonModelBuilder compiler;
    private spoon.Launcher spoon;

    private final Logger logger = LoggerFactory.getLogger(Launcher.class);

    public Launcher(String[] sourcePath, String sourceOutput, String binOutput, String classpath) {
        this.sourcePath = sourcePath;
        if(!File.pathSeparator.equals(classpath.charAt(classpath.length() - 1) + "")) {
            classpath = classpath + File.pathSeparator;
        }

        this.classpath = classpath + System.getProperty("java.class.path");
        this.sourceOutput = sourceOutput;
        this.binOutput = binOutput;
        this.compiler = init();
        spoon.setSourceOutputDirectory(sourceOutput);
        spoon.setBinaryOutputDirectory(binOutput);
    }

    /**
     *
     */
    public void instrument() {
        ProcessingManager p = new QueueProcessingManager(spoon.getFactory());

        p.addProcessor(new TernarySplitter());
        //p.addProcessor(new IfSplitter());
        p.addProcessor(new CheckNotNull());
        p.addProcessor(new ForceNullInit());
        p.addProcessor(new AddImplicitCastChecker());
        p.addProcessor(new BeforeDerefAdder());
        p.addProcessor(new TargetModifier());
        p.addProcessor(new TryRegister());
        p.addProcessor(new VarRetrieveAssign());
        p.addProcessor(new VarRetrieveInit());
        p.addProcessor(new MethodEncapsulation());
        p.addProcessor(new ConstructorEncapsulation());
        p.addProcessor(new VariableFor());

        logger.debug("Start code instrumentation");

        Set<CtType<?>> allWithoutTest = getAllClasses();
        p.process(allWithoutTest);

        spoon.prettyprint();

        compiler.compile();
        logger.debug("End code instrumentation");
    }

    /**
     * Get all classes without tests
     * @return
     */
    private Set<CtType<?>> getAllClasses() {
        Set<CtType<?>> allWithoutTest = new HashSet<>();
        List<CtType<?>> allClasses = spoon.getFactory().Class().getAll();
        for (int i = 0; i < allClasses.size(); i++) {
            CtType<?> ctType = allClasses.get(i);
            if(ctType.getSimpleName().endsWith("Test")) {
                continue;
            }
            if(ctType.getPosition().getFile().getAbsolutePath().contains("/test/")) {
                continue;
            }
            // junit 4
            List<CtElement> elements = ctType.getElements(new AnnotationFilter<>(Test.class));
            if(elements.size() > 0) {
                continue;
            }
            // junit 3
            if(containsJunit(ctType.getSuperclass())) {
               continue;
            }
            allWithoutTest.add(ctType);
        }
        return allWithoutTest;
    }

    private boolean containsJunit(CtTypeReference<?> ctType) {
        if(ctType == null) {
            return false;
        }
        if(ctType.getQualifiedName().contains("junit")) {
            return true;
        }
        return containsJunit(ctType.getSuperclass());
    }

    private void copyResources() {
        File directory = new File(sourceOutput);
        if(!directory.exists()) {
            directory.mkdirs();
        }
        Collection resources = FileUtils.listFiles(directory, spoon.RESOURCES_FILE_FILTER, spoon.ALL_DIR_FILTER);
        Iterator var6 = resources.iterator();

        while(var6.hasNext()) {
            Object resource = var6.next();
            String resourceParentPath = ((File)resource).getParent();
            String packageDir = resourceParentPath.substring(directory.getPath().length());
            packageDir = packageDir.replace("/java", "").replace("/resources", "");
            String targetDirectory = this.binOutput + packageDir;

            try {
                FileUtils.copyFileToDirectory((File) resource, new File(targetDirectory));
            } catch (IOException var12) {
                throw new SpoonException(var12);
            }
        }
    }

    private SpoonModelBuilder init() {
        spoon = new spoon.Launcher();
        for (int i = 0; i < sourcePath.length; i++) {
            String s = sourcePath[i];
            if(s != null) {
                spoon.addInputResource(s);
            }
        }

        SpoonModelBuilder compiler = spoon.getModelBuilder();
        compiler.setSourceClasspath(classpath.split(File.pathSeparator));

        spoon.getEnvironment().setCopyResources(true);
        spoon.getEnvironment().setAutoImports(true);
        spoon.getEnvironment().setShouldCompile(false);
        spoon.getEnvironment().setCommentEnabled(false);
        spoon.getEnvironment().setComplianceLevel(7);
        spoon.getEnvironment().setLevel("OFF");
        spoon.buildModel();
        copyResources();
        return compiler;
    }

    public NPEOutput run() {
        return run(new RandomSelector());
    }

    public NPEOutput run(Selector selector) {
        List<String> methodTests = getTests();

        return run(selector, methodTests);
    }

    public NPEOutput run(Selector selector, List<String> methodTests) {
        CallChecker.enable();
        CallChecker.strategySelector = selector;

        NPEOutput output = new NPEOutput();

        Lapse lapse = new Lapse(selector);

        final TestRunner testRunner = new TestRunner();
        for (int i = 0; i < methodTests.size(); i++) {
            String method = methodTests.get(i);
            String[] split = method.split("#");
            method = split[1];
            String className = split[0];

            String[] sourceClasspath = spoon.getModelBuilder().getSourceClasspath();
            URLClassLoader urlClassLoader = getUrlClassLoader(sourceClasspath);

            CallChecker.currentClassLoader = urlClassLoader;

            final Request request;
            try {
                request = Request.method(urlClassLoader.loadClass(className), method);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                continue;
            }

            lapse.setTestClassName(className);
            lapse.setTestName(method);
            try {
                selector.startLaps(lapse);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            Result result = testRunner.run(request);

            lapse.setOracle(new TestOracle(result));

            System.out.println(lapse);
            if(result.getRunCount() > 0) {
                output.add(lapse);
                try {
                    if (selector.restartTest(lapse)) {
						/*Map<Location, Integer> currentIndex = new HashMap<>(laps.getCurrentIndex());
						Map<Location, Map<String, Object>> possibleVariables = laps
								.getPossibleVariables();
						Map<Location, List<Object>> possibleValues = laps
								.getPossibleValues();

						Set<Location> locations = currentIndex.keySet();
						for (Iterator<Location> iterator = locations
								.iterator(); iterator.hasNext(); ) {
							Location location = iterator.next();

							if(possibleVariables != null
									&& possibleVariables.containsKey(location)
									&& possibleVariables.get(location).size() > currentIndex.get(location) + 1) {
								currentIndex.put(location, currentIndex.get(location) + 1);
							} else if ( possibleValues != null
									&& possibleValues.containsKey(location)
									&& possibleValues.get(location).size() > currentIndex.get(location) + 1) {
								currentIndex.put(location, currentIndex.get(location) + 1);
							}
						}*/

                        lapse = new Lapse(selector);
						/*laps.setCurrentIndex(currentIndex);
						laps.setPossibleValues(possibleValues);
						laps.setPossibleVariables(possibleVariables);
						i--;*/
                    } else {
                        lapse = new Lapse(selector);
                    }
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            } else {
                lapse = new Lapse(selector);
            }
            CallChecker.enable();
            CallChecker.cache.clear();
            CallChecker.getDecisions().clear();
        }
        Collections.sort(output);
        return output;
    }

    private static void inheritIO(final InputStream src, final PrintStream dest) {
        new Thread(new Runnable() {
            public void run() {
                Scanner sc = new Scanner(src);
                while (sc.hasNextLine()) {
                    dest.println(sc.nextLine());
                }
            }
        }).start();
    }

    public NPEOutput runCommandLine(Selector selector, List<String> methodTests) {

        CallChecker.enable();
        CallChecker.strategySelector = selector;

        NPEOutput output = new NPEOutput();

        for (int i = 0; i < methodTests.size(); i++) {
            String method = methodTests.get(i);
            String[] split = method.split("#");
            method = split[1];
            String className = split[0];
            String separator = System.getProperty("file.separator");
            String path = System.getProperty("java.home")
                    + separator + "bin" + separator + "java";
            ProcessBuilder processBuilder =
                    new ProcessBuilder(path, "-cp",
                            classpath,
                            ExecutionClient.class.getName(),
                            className,
                            method,
                            Config.CONFIG.getRandomSeed() + "");
            try {
                // run the new JVM
                final Process process = processBuilder.start();
                // print the output to the current console
                inheritIO(process.getInputStream(), System.out);
                inheritIO(process.getErrorStream(), System.err);
                // wait the end of the process
                process.waitFor();
                // adds all lapse
                List<Lapse> lapses = new ArrayList<>();
                for (Lapse lapse : selector.getLapses()) {
                    if (lapse.getOracle() instanceof ExceptionOracle) {
                        // removes lapse that end because there is not more available decision (Full exploration strategy)
                        if (!lapse.getOracle().isValid()
                                && lapse.getOracle().getError().contains("No more available decision")) {
                            continue;
                        }
                    }
                    lapses.add(lapse);
                }
                output.addAll(lapses);
                selector.getLapses().clear();
                // destroy the process
                process.destroy();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Collections.sort(output);
        return output;
    }

	/**
     * Returns all test methods of the spooned project
     * @return a list of test methods
     */
    public List<String> getTests() {
        String[] sourceClasspath = spoon.getModelBuilder().getSourceClasspath();

        URLClassLoader urlClassLoader = getUrlClassLoader(sourceClasspath);

        CallChecker.currentClassLoader = urlClassLoader;

        return getTests(spoon, urlClassLoader);
    }

    public static List<String> getTests(spoon.Launcher spoon, URLClassLoader urlClassLoader) {
        String[] testsString = new TestClassesFinder().findIn(urlClassLoader, false);
        Class[] tests = filterTest(spoon, urlClassLoader, testsString);

        List<String> methodTests = new ArrayList();
        for (int i = 0; i < tests.length; i++) {
            Class test = tests[i];
            Method[] methods = test.getDeclaredMethods();
            for (int j = 0; j < methods.length; j++) {
                Method method = methods[j];
                if(method.getName().equals("setUp")) {
                    continue;
                }
                if(method.getName().equals("tearDown")) {
                    continue;
                }

                if(method.getReturnType().equals(void.class)
                        && method.getParameterTypes().length == 0) {
                    if(!method.isAnnotationPresent(After.class)
                            && !method.isAnnotationPresent(AfterClass.class)
                            && !method.isAnnotationPresent(Before.class)
                            && !method.isAnnotationPresent(BeforeClass.class)
                            && !method.isAnnotationPresent(Override.class)) {
                        methodTests.add(test.getCanonicalName() + "#" + method.getName());
                    }
                }
            }
        }
        return methodTests;
    }

    public NPEOutput runStrategy(Strategy...strategies) {
        NPEOutput output = new NPEOutput();

        Selector selector = new DomSelector();
        DecisionServer decisionServer = new DecisionServer(selector);
        decisionServer.startServer();

        List<String> tests = getTests();
        for (int i = 0; i < strategies.length; i++) {
            Strategy strategy = strategies[i];
            System.out.println(strategy);
            DomSelector.strategy = strategy;
            NPEOutput run = run(selector, tests);
            // NPEOutput run = runCommandLine(selector, tests);
            output.addAll(run);
        }
        Collections.sort(output);
        return output;
    }

    private static Class[] filterTest(spoon.Launcher spoon, URLClassLoader urlClassLoader, String[] testsString) {
        Set<Class> tests = new HashSet<>();
        for (int i = 0; i < testsString.length; i++) {
            String s = testsString[i];
            if(!isValidTest(spoon, s)) {
                continue;
            }
            try {
                Class<?> aClass = urlClassLoader.loadClass(s);
                tests.add(aClass);
            } catch (ClassNotFoundException e) {
                continue;
            }
        }
        return tests.toArray(new Class[]{});
    }

    public static URLClassLoader getUrlClassLoader(String[] sourceClasspath) {
        ArrayList<URL> uRLClassPath = new ArrayList<>();
        for (int i = 0; i < sourceClasspath.length; i++) {
            String s = sourceClasspath[i];
            try {
                uRLClassPath.add(new File(s).toURL());
            } catch (MalformedURLException e) {
                continue;
            }
        }
        return new URLClassLoader(uRLClassPath.toArray(new URL[]{}));
    }

    private static boolean isValidTest(spoon.Launcher spoon, String testName) {
        return spoon.getFactory().Class().get(testName) != null;
    }

    public spoon.Launcher getSpoon() {
        return spoon;
    }

    public SpoonModelBuilder getCompiler() {
        return compiler;
    }
}
