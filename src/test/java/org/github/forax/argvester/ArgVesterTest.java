package org.github.forax.argvester;

import org.github.forax.argvester.ArgVester.ArgumentParsingException;
import org.github.forax.argvester.ArgVester.Opt;
import org.github.forax.argvester.ArgVester.Opt.Kind;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArgVesterTest {
  @Test
  public void onlyPositional() {
    record OnlyPositional(
        String login,
        String password
    ) { }

    var argVester = ArgVester.create(MethodHandles.lookup(), OnlyPositional.class);
    var onlyPositional = argVester.parse(new String[] { "foo", "pa33w0rd"});
    assertAll(
        () -> assertEquals("foo", onlyPositional.login),
        () -> assertEquals("pa33w0rd", onlyPositional.password)
    );
  }

  @Test
  public void positionalConversions() {
    enum Color { red, green, blue }
    record Conversion (
        String string,
        Path path,
        int integer,
        double number,
        boolean flag,
        Color color
    ) { }

    var argVester = ArgVester.create(MethodHandles.lookup(), Conversion.class);
    var conversion = argVester.parse(new String[] { "foo", "bar.txt", "3", "3.14", "true", "red"});
    assertAll(
        () -> assertEquals("foo", conversion.string),
        () -> assertEquals(Path.of("bar.txt"), conversion.path),
        () -> assertEquals(3, conversion.integer),
        () -> assertEquals(3.14, conversion.number),
        () -> assertTrue(conversion.flag),
        () -> assertEquals(Color.red, conversion.color)
    );
  }

  @Test
  public void optionalConversions() {
    enum Color { red, green, blue }
    record Conversion (
        Optional<String> string,
        Optional<Path> path,
        Optional<Integer> integer,
        Optional<Double> number,
        Optional<Boolean> flag,
        Optional<Color> color
    ) { }

    var argVester = ArgVester.create(MethodHandles.lookup(), Conversion.class);
    var conversion = argVester.parse("""
       --path:bar.txt
       --string:foo
       --color:green
       --number:3.14
       --flag:true
       --integer:3
       """.lines().toArray(String[]::new));
    assertAll(
        () -> assertEquals("foo", conversion.string.orElseThrow()),
        () -> assertEquals(Path.of("bar.txt"), conversion.path.orElseThrow()),
        () -> assertEquals(3, conversion.integer.orElseThrow()),
        () -> assertEquals(3.14, conversion.number.orElseThrow()),
        () -> assertEquals(true, conversion.flag.orElseThrow()),
        () -> assertEquals(Color.green, conversion.color.orElseThrow())
    );
  }

  @Test
  public void positionalAndOptional() {
    record PositionalOrOptional(
        String id,
        Optional<String> level,
        Optional<Integer> supplementary_info
    ) { }

    var argVester = ArgVester.create(MethodHandles.lookup(), PositionalOrOptional.class);
    assertAll(
        () -> {
          var positionalOrOptional = argVester.parse(new String[] { "foo304", "--level", "error", "--supplementary-info", "1"});
          assertEquals("foo304", positionalOrOptional.id);
          assertEquals("error", positionalOrOptional.level.orElseThrow());
          assertEquals(1, positionalOrOptional.supplementary_info.orElseThrow());
        },
        () -> {
          var positionalOrOptional = argVester.parse(new String[] { "--level", "error", "foo304", "--supplementary-info", "1"});
          assertEquals("foo304", positionalOrOptional.id);
          assertEquals("error", positionalOrOptional.level.orElseThrow());
          assertEquals(1, positionalOrOptional.supplementary_info.orElseThrow());
        },
        () -> {
          var positionalOrOptional = argVester.parse(new String[] { "--supplementary-info", "1", "--level", "error", "foo304"});
          assertEquals("foo304", positionalOrOptional.id);
          assertEquals("error", positionalOrOptional.level.orElseThrow());
          assertEquals(1, positionalOrOptional.supplementary_info.orElseThrow());
        }
    );
  }

  @Test
  public void example() {
    enum LogLevel { error, warning }

    record Option(
        // positional argument
        // java MyClass <config_file>
        Path config_file,

        // optional argument
        // short or long version
        //   e.g., -b or --bind-address
        // arguments can be delimited by ' ', '=' or ':'
        //   e.g., -b:192.168.0.10
        //   e.g., --bind-address=192.168.0.10
        //   e.g., --bind-address 192.168.0.10
        Optional<String> bind_address,

        // use enum to restrict possible arguments
        Optional<LogLevel> log_level,

        // use record to have sub arguments
        //   e.g --user <first> <second>
        // Optional<SubOption> user,

        // flag argument
        // -v or --verbose
        Optional<Boolean> verbose,

        // variadic argument
        // use a collection like java.util.List or java.util.Set
        List<String> filenames
    ) {}

    var argVester = ArgVester.create(MethodHandles.lookup(), Option.class);
    var args = "file.conf --bind-address 192.168.0.10 --log-level:warning -v foo.txt bar.txt".split(" ");
    var option = argVester.parse(args);

    assertAll(
        () -> assertEquals(Path.of("file.conf"), option.config_file),
        () -> assertEquals("192.168.0.10", option.bind_address.orElseThrow()),
        () -> assertEquals(LogLevel.warning, option.log_level.orElseThrow()),
        () -> assertEquals(true, option.verbose.orElseThrow()),
        () -> assertEquals(List.of("foo.txt", "bar.txt"), option.filenames)
    );
  }

  @Test
  public void copy() {
    record Copy(
        // optional argument
        Optional<Boolean> pretend,
        // positional argument
        Path source,
        // positional argument
        Path target
    ) {}

    var argVester = ArgVester.create(MethodHandles.lookup(), Copy.class);
    var args = "--pretend foo.txt bar.txt".split(" ");
    var copy = argVester.parse(args);
    assertAll(
        () -> assertEquals(Path.of("foo.txt"), copy.source),
        () -> assertEquals(Path.of("bar.txt"), copy.target),
        () -> assertEquals(true, copy.pretend.orElseThrow())
    );
  }

  @Test
  public void download() {
    record Download(
        // positional
        Path directory,
        // optional
        List<String> checksum,
        // positional
        String source
    ) {}

    var argVester = ArgVester.create(MethodHandles.lookup(), Download.class);
    var args = ". --checksum md5=38423987 --checksum sha256=387348975345797 https://foo.com/".split(" ");
    var download = argVester.parse(args);
    assertAll(
        () -> assertEquals(Path.of("."), download.directory),
        () -> assertEquals("https://foo.com/", download.source),
        () -> assertEquals(List.of("md5=38423987", "sha256=387348975345797"), download.checksum)
    );
  }

  @Test
  public void explicitKind() {
    record Explicit(
        @Opt(kind= Kind.OPTIONAL)
        Optional<Boolean> opt1,
        @Opt(kind= Kind.OPTIONAL)
        Optional<String> opt2,
        @Opt(kind= Kind.OPTIONAL)
        List<String> opt3,
        @Opt(kind= Kind.POSITIONAL)
        String pos,
        @Opt(kind= Kind.VARIADIC)
        List<String> variad
    ) {}
    var argVester = ArgVester.create(MethodHandles.lookup(), Explicit.class);
    var args = """
      --opt1
      --opt2 foo
      --opt3 bar
      --opt3 baz
      whizz
      fuzz
      fuzz2
      """.lines().flatMap(l -> Arrays.stream(l.split(" "))).toArray(String[]::new);
    var explicit = argVester.parse(args);
    assertAll(
        () -> assertTrue(explicit.opt1.orElseThrow()),
        () -> assertEquals("foo", explicit.opt2.orElseThrow()),
        () -> assertEquals(List.of("bar", "baz"), explicit.opt3),
        () -> assertEquals("whizz", explicit.pos),
        () -> assertEquals(List.of("fuzz", "fuzz2"), explicit.variad)
    );
  }

  @Test
  public void help() {
    enum LogLevel { error, warning }
    record Option(
        // positional argument
        @Opt(help = "the configuration file")
        Path config_file,

        // optional argument
        // short or long version
        //   e.g., -b or --bind-address
        // arguments can be delimited by ' ', '=' or ':'
        //   e.g., -b:192.168.0.10
        //   e.g., --bind-address=192.168.0.10
        //   e.g., --bind-address 192.168.0.10
        @Opt(valueHelp = "address", help = "bind address of the service")
        Optional<String> bind_address,

        // use enum to restrict possible arguments
        @Opt(valueHelp = "level", help = "logger level")
        Optional<LogLevel> log_level,

        // flag argument
        // -v or --verbose
        @Opt(help = "logged data verbose mode")
        Optional<Boolean> verbose,

        // variadic argument
        // use a collection like java.util.List or java.util.Set
        @Opt(help = "file names exposed as services")
        List<String> filenames
    ) {}

    var argVester = ArgVester.create(MethodHandles.lookup(), Option.class);
    assertEquals("""
        netapp [options] <config-file> <filenames...>
          with:
            config-file: the configuration file
            filenames: file names exposed as services
        
          options:
            bind-address: bind address of the service
              -b address or --bind-address address
            log-level: logger level
              -l level or --log-level level
            verbose: logged data verbose mode
              -v or --verbose
        """, argVester.toHelp("netapp"));
  }

  @Test
  public void helpNoOption() {
    record Option(
        // positional argument
        @Opt(help = "user name")
        String user,

        // variadic argument
        @Opt(help = "file names")
        List<String> filenames
    ) {}

    var argVester = ArgVester.create(MethodHandles.lookup(), Option.class);
    assertEquals("""
        myapp <user> <filenames...>
          with:
            user: user name
            filenames: file names
        """, argVester.toHelp("myapp"));
  }
  @Test
  public void helpNoPositional() {
    record Option(
        // optional argument
        @Opt(help = "user name")
        Optional<String> user,

        // variadic argument
        @Opt(help = "file names")
        Set<String> filenames
    ) {}

    var argVester = ArgVester.create(MethodHandles.lookup(), Option.class);
    assertEquals("""
        myapp [options] <filenames...>
          with:
            filenames: file names
        
          options:
            user: user name
              -u user or --user user
        """, argVester.toHelp("myapp"));
  }

  @Test
  public void parsingExceptions() {
    record Option(
        int value,
        Optional<Double> level,
        Optional<Boolean> flag,
        List<Integer> integers
    ) { }

    var argVester = ArgVester.create(MethodHandles.lookup(), Option.class);
    assertAll(
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("12 --bang".split(" "))),  // unknown option
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("bang".split(" "))),       // first positional not an integer
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("12 --level bang".split(" "))),   // level argument not a double
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("12 --level bang --level foo".split(" "))),   // level argument can not be repeated
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("12 --flag --flag".split(" "))),   // flag argument can not be repeated
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("12 --level 4 bang".split(" "))), // variadic argument not an int
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("12 bang".split(" "))), // variadic argument not an int
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse(new String[0])), // positional argument required
        () -> assertThrows(ArgumentParsingException.class, () -> argVester.parse("--level 3.4".split(" "))) // positional argument required
    );
  }
}