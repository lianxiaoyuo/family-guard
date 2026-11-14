import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.util.Arrays;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Checks syntax without needing Android SDK classes or resolving dependencies. */
public final class JavaSyntaxCheck {
    public static void main(String[] args) throws IOException {
        if (args.length == 0) throw new IllegalArgumentException("supply .java files");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task=(JavacTask)javac.getTask(null,fm,diagnostics,Arrays.asList("-proc:none"),null,fm.getJavaFileObjects(args));
            task.parse();
        }
        long errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).count();
        for (Diagnostic<? extends JavaFileObject> d:diagnostics.getDiagnostics())
            if (d.getKind()==Diagnostic.Kind.ERROR) System.err.println(d);
        if (errors>0) System.exit(1);
        System.out.println("Java syntax PASS: "+args.length+" files");
    }
}
