package org.logstash.config.ir.compiler;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.ISimpleCompiler;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.SimpleCompiler;

/**
 * One step of a compiled pipeline that compiles to a {@link Dataset}.
 */
public final class ComputeStepSyntaxElement<T extends Dataset> {

    public static final VariableDefinition CTOR_ARGUMENT =
        new VariableDefinition(Map.class, "arguments");

    private static final Path SOURCE_DIR = debugDir();

    private static final ISimpleCompiler COMPILER = new SimpleCompiler();

    /**
     * Cache of runtime compiled classes to prevent duplicate classes being compiled.
     */
    private static final Map<ComputeStepSyntaxElement<?>, Class<? extends Dataset>> CLASS_CACHE
        = new HashMap<>();

    /**
     * Pattern to remove redundant {@code ;} from formatted code since {@link Formatter} does not
     * remove those.
     */
    private static final Pattern REDUNDANT_SEMICOLON = Pattern.compile("\n[ ]*;\n");

    private final Iterable<MethodSyntaxElement> methods;

    private final ClassFields fields;

    private final Class<T> type;

    public static <T extends Dataset> ComputeStepSyntaxElement<T> create(
        final Iterable<MethodSyntaxElement> methods, final ClassFields fields,
        final Class<T> interfce) {
        return new ComputeStepSyntaxElement<>(methods, fields, interfce);
    }

    private ComputeStepSyntaxElement(final Iterable<MethodSyntaxElement> methods,
        final ClassFields fields, final Class<T> interfce) {
        this.methods = methods;
        this.fields = fields;
        type = interfce;
    }

    @SuppressWarnings("unchecked")
    public T instantiate() {
        // We need to globally synchronize to avoid concurrency issues with the internal class
        // loader and the CLASS_CACHE
        synchronized (COMPILER) {
            try {
                final Class<? extends Dataset> clazz;
                if (CLASS_CACHE.containsKey(this)) {
                    clazz = CLASS_CACHE.get(this);
                } else {
                    final String name = String.format("CompiledDataset%d", CLASS_CACHE.size());
                    final String code = generateCode(name);
                    if (SOURCE_DIR != null) {
                        final Path sourceFile = SOURCE_DIR.resolve(String.format("%s.java", name));
                        Files.write(sourceFile, code.getBytes(StandardCharsets.UTF_8));
                        COMPILER.cookFile(sourceFile.toFile());
                    } else {
                        COMPILER.cook(code);
                    }
                    COMPILER.setParentClassLoader(COMPILER.getClassLoader());
                    clazz = (Class<T>) COMPILER.getClassLoader().loadClass(
                        String.format("org.logstash.generated.%s", name)
                    );
                    CLASS_CACHE.put(this, clazz);
                }
                return (T) clazz.<T>getConstructor(Map.class).newInstance(ctorArguments());
            } catch (final CompileException | ClassNotFoundException | IOException
                | NoSuchMethodException | InvocationTargetException | InstantiationException
                | IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @Override
    public int hashCode() {
        return normalizedSource().hashCode();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof ComputeStepSyntaxElement &&
            normalizedSource().equals(((ComputeStepSyntaxElement<?>) other).normalizedSource());
    }

    private String generateCode(final String name) {
        try {
            return REDUNDANT_SEMICOLON.matcher(new Formatter().formatSource(
                String.format(
                    "package org.logstash.generated;\npublic final class %s extends org.logstash.config.ir.compiler.BaseDataset implements %s { %s }",
                    name,
                    type.getName(),
                    SyntaxFactory.join(
                        fields.inlineAssigned().generateCode(), fieldsAndCtor(name),
                        combine(
                            StreamSupport.stream(methods.spliterator(), false)
                                .toArray(SyntaxElement[]::new)
                        )
                    )
                )
            )).replaceAll("\n");
        } catch (final FormatterException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Path debugDir() {
        Path sourceDir = null;
        try {
            final Path parentDir;
            final String dir = System.getProperty(Scanner.SYSTEM_PROPERTY_SOURCE_DEBUGGING_DIR);
            if (dir != null) {
                parentDir = Paths.get(dir);
                sourceDir = parentDir.resolve("org").resolve("logstash").resolve("generated");
                Files.createDirectories(sourceDir);
            }
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return sourceDir;
    }

    /**
     * @return Array of constructor arguments
     */
    private Map<String, Object> ctorArguments() {
        final Map<String, Object> result = new HashMap<>();
        fields.ctorAssigned().getFields().forEach(
            fieldDefinition ->
                result.put(fieldDefinition.getName(), fieldDefinition.getCtorArgument())
        );
        return result;
    }

    /**
     * Normalizes away the name of the class so that two classes of different name but otherwise
     * equivalent syntax get correctly compared by {@link #equals(Object)}.
     * @return Source of this class, with its name set to {@code CONSTANT}.
     */
    private String normalizedSource() {
        return this.generateCode("CONSTANT");
    }

    /**
     * Generates the Java code for defining one field and constructor argument for each given value.
     * constructor for
     * @return Java Source String
     */
    private String fieldsAndCtor(final String name) {
        final Closure constructor = new Closure();
        final FieldDeclarationGroup ctorFields = fields.ctorAssigned();
        for (final FieldDefinition field : ctorFields.getFields()) {
            if (field.getCtorArgument() != null) {
                final VariableDefinition fieldVar = field.asVariable();
                constructor.add(
                    SyntaxFactory.assignment(
                        fieldVar.access(),
                        SyntaxFactory.cast(
                            fieldVar.type,
                            CTOR_ARGUMENT.access().call(
                                "get",
                                SyntaxFactory.value(
                                    SyntaxFactory.join("\"", field.getName(), "\"")
                                )
                            )
                        )
                    )
                );
            }
        }
        return combine(
            ctorFields,
            MethodSyntaxElement.constructor(
                name, constructor.add(fields.afterInit())
            )
        );
    }

    /**
     * Renders the string concatenation of the given {@link SyntaxElement}, delimited by
     * line breaks.
     * @param parts Elements to concatenate
     * @return Java source
     */
    private static String combine(final SyntaxElement... parts) {
        return Arrays.stream(parts).map(SyntaxElement::generateCode)
            .collect(Collectors.joining("\n"));
    }
}
