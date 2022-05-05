package org.github.forax.argvester;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * ArgVester is a command line arguments harvester.
 *
 * ArgVester is {@link #create(Lookup, Class) created} from a record class
 * and {@link #parse(String[])} the command line arguments using the record components
 * as a meta description.
 *
 * The command line is defined by three kinds of arguments
 * <ol>
 *   <li>positional arguments, required arguments (in order)
 *   <li>optional arguments, arguments composed of a name prefixed by either "-" or "--" and a value
 *       (a boolean option has no value)
 *   <li>a variadic argument, the last arguments are grouped together in a list
 * </ol>
 * Inside a record, the arguments need to defined in the same order i.e.
 * first the positional arguments then the optional arguments and at the end
 * optionally a variadic argument.
 *
 * Here is an example of record used to define a command line
 * <pre>
 *   enum LogLevel { error, warning }
 *   record Opt(
 *      // positional argument
 *      // java MyClass &lt;config_file&gt;
 *      Path config_file,
 *
 *      // optional argument
 *      // short or long version
 *      //   e.g., -b or --bind-address
 *      // arguments can be delimited by ' ', '=' or ':'
 *      //   e.g., -b:192.168.0.10
 *      //   e.g., --bind-address=192.168.0.10
 *      //   e.g., --bind-address 192.168.0.10
 *      Optional&lt;String&gt; bind_address,
 *
 *      // use enum to restrict possible arguments
 *      //   e.g., --log-level error
 *      Optional&lt;LogLevel&gt; log_level,
 *
 *      // optional flag argument
 *      //   e.g, -v or --verbose
 *      Optional&lt;Boolean&gt; verbose,
 *
 *      // variadic argument
 *      // use a collection like java.util.List or java.util.Set
 *      List&lt;String&gt; filenames
 *   ) {}
 * </pre>
 */
public class ArgVester<R extends Record> {
  private record Info(String name, String abbrev, String help, String valueHelp) {
    String description() {
      return name + ": " + help;
    }

    static Info create(RecordComponent component) {
      var opt = component.getAnnotation(Opt.class);
      var name = Optional.ofNullable(opt).map(Opt::name).filter(not(String::isEmpty)).orElseGet(() -> component.getName().replace('_', '-'));
      var abbrev = Optional.ofNullable(opt).map(Opt::abbrev).filter(not(String::isEmpty)).orElseGet(() -> "" + name.charAt(0));
      var help = Optional.ofNullable(opt).map(Opt::help).filter(not(String::isEmpty)).orElse("");
      var valueHelp = Optional.ofNullable(opt).map(Opt::valueHelp).filter(not(String::isEmpty)).orElse(name);
      return new Info(name, abbrev, help, valueHelp);
    }
  }
  private sealed interface Arg {
    Info info();
    Function<String, ?> converter();

    default Object convert(String s) {
      var converter = converter();
      try {
        return converter.apply(s);
      } catch(RuntimeException e) {
        throw new ArgumentParsingException("invalid conversion while parsing argument " + info().name, e);
      }
    }
  }
  private record OptionalArg(Info info, Function<String, ?> converter, int position, boolean flag) implements Arg {
    String withParam(String optionName) {
      return flag? optionName: optionName + " " + info.valueHelp;
    }
  }
  private record PositionalArg(Info info, Function<String, ?> converter) implements Arg {}
  private record VariadicArg(Info info, Function<String, ?> converter, Collector<Object, ?, ?> collector) implements Arg {}

  private final Constructor<R> constructor;
  private final ArrayList<PositionalArg> positionalArgs;
  private final LinkedHashMap<String, OptionalArg> optionalArgs;
  private final VariadicArg variadicArg;

  private ArgVester(Constructor<R> constructor,
                    ArrayList<PositionalArg> positionalArgs, LinkedHashMap<String, OptionalArg> optionalArgs, VariadicArg variadicArg) {
    this.constructor = constructor;
    this.positionalArgs = positionalArgs;
    this.optionalArgs = optionalArgs;
    this.variadicArg = variadicArg;
  }

  /**
   * Thrown when the record meta description is invalid.
   *
   * @see #create(Lookup, Class)
   */
  public static class InvalidMetaDescriptionException extends RuntimeException {
    private static final long serialVersionUID = -3116126263566500943L;

    public InvalidMetaDescriptionException(String message) {
      super(message);
    }

    public InvalidMetaDescriptionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Create an argument harvester from a meta description provided as a record.
   * @param lookup the lookup used to find the record constructor.
   * @param schema a record class used as a meta description.
   * @param <R> the type of the record.
   * @return an instance of an arguments harvester configured with the record meta description.
   * @throws InvalidMetaDescriptionException if the meta description is invalid,
   *         if the argument kinds (positional, optional, variadic) are not in that order,
   *         if the type of an argument is unknown (only String, Path and boolean, int, double are supported),
   *         if the variadic argument type is not a list or a set.
   */
  public static <R extends Record> ArgVester<R> create(Lookup lookup, Class<R> schema) throws InvalidMetaDescriptionException {
    requireNonNull(lookup);
    requireNonNull(schema);

    // scan to find all arguments in the schema
    var positionalArgs = new ArrayList<PositionalArg>();
    var optionalArgs = new LinkedHashMap<String, OptionalArg>();
    VariadicArg variadicArg = null;
    var recordComponents = schema.getRecordComponents();
    var argCount = recordComponents.length;

    // positional arguments
    var i = 0;
    for (; i < argCount; i++) {
      var component = recordComponents[i];
      var type = component.getGenericType();
      if (type instanceof Class<?> clazz) {
        var positionalArg = new PositionalArg(Info.create(component), converter(component, clazz));
        positionalArgs.add(positionalArg);
        continue;
      }
      break;
    }

    // optional arguments
    for (; i < argCount; i++) {
      var component = recordComponents[i];
      var type = component.getGenericType();
      if (type instanceof ParameterizedType parameterizedType) {
        var rawType = parameterizedType.getRawType();
        if (rawType == Optional.class) {
          var typeArgument = parameterizedType.getActualTypeArguments()[0];
          if (typeArgument instanceof Class<?> clazz) {
            var info = Info.create(component);
            OptionalArg optionalArg;
            if (clazz == Boolean.class) {
              optionalArg = new OptionalArg(info, Boolean::parseBoolean, i, true);
            } else {
              optionalArg = new OptionalArg(info, converter(component, clazz), i, false);
            }
            optionalArgs.put("-" + info.abbrev, optionalArg);
            optionalArgs.put("--" + info.name, optionalArg);
            continue;
          }
        }
      }
      break;
    }

    // variadic argument
    if (i == argCount -1) {
      var component = recordComponents[i];
      var type = component.getGenericType();
      if (type instanceof ParameterizedType parameterizedType) {
        var rawType = parameterizedType.getRawType();
        if (rawType instanceof Class<?> rawClass && Collection.class.isAssignableFrom(rawClass)) {
          var typeArgument = parameterizedType.getActualTypeArguments()[0];
          if (typeArgument instanceof Class<?> clazz) {
            variadicArg = new VariadicArg(Info.create(component), converter(component, clazz), collector(component, rawClass));
            i++;
          }
        }
      }
    }

    // error
    if (i != argCount) {
      var component = recordComponents[i];
      throw new InvalidMetaDescriptionException("unrecognized record component type " + component);
    }

    MethodHandle mh;
    try {
      mh = lookup.findConstructor(schema, methodType(void.class,
          Arrays.stream(recordComponents).map(RecordComponent::getType).toArray(Class[]::new)));
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    }

    @SuppressWarnings("unchecked")
    var constructor = (Constructor<R>) MethodHandles.reflectAs(Constructor.class, mh);

    return new ArgVester<>(constructor, positionalArgs, optionalArgs, variadicArg);
  }

  private static Collector<Object,?,?> collector(RecordComponent component, Class<?> collectionClass) {
    if (collectionClass == List.class) {
      return Collectors.toUnmodifiableList();
    }
    if (collectionClass == Set.class) {
      return Collectors.toUnmodifiableSet();
    }
    if (collectionClass == ArrayList.class) {
      return Collectors.toCollection(ArrayList::new);
    }
    if (collectionClass == HashSet.class) {
      return Collectors.toCollection(HashSet::new);
    }
    if (collectionClass == LinkedHashSet.class) {
      return Collectors.toCollection(LinkedHashSet::new);
    }
    throw new InvalidMetaDescriptionException("don't know how to create a " + collectionClass + " for component " + component);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Function<String, ?> converter(RecordComponent component, Class<?> toClass) {
    if (toClass == String.class) {
      return s -> s;
    }
    if (toClass == Integer.class || toClass == int.class) {
      return Integer::parseInt;
    }
    if (toClass == Double.class || toClass == double.class) {
      return Double::parseDouble;
    }
    if (toClass == Boolean.class || toClass == boolean.class) {
      return Boolean::parseBoolean;
    }
    if (toClass == Path.class) {
      return Path::of;
    }
    if (toClass.isEnum()) {
      return s -> Enum.valueOf((Class)toClass, s);
    }
    throw new InvalidMetaDescriptionException("no way to convert a String to a " + toClass + " for " + component);
  }

  /**
   * Annotation to refine the meta description of a record component corresponding to a command line argument.
   * This annotation is not required and allows to enhance the meta description of an argument.
   */
  @Target(RECORD_COMPONENT)
  @Retention(RUNTIME)
  public @interface Opt {
    /**
     * Name of the argument
     * @return the name of the argument
     */
    String name() default "";

    /**
     * Abbreviation used if the argument is an optional argument
     * @return the name of the abbreviation
     */
    String abbrev() default "";

    /**
     * The help line used by {@link #toHelp(String)}
     * @return the help line of the argument
     */
    String help() default "";

    /**
     * A text representation of the value of an optional (non boolean) argument
     * @return A text representation of the value of an argument
     */
    String valueHelp() default "";
  }

  /**
   * Print an help message from the meta description
   *
   * By example with the record
   * <pre>
   *   enum LogLevel { error, warning }
   *   record Option(
   *       // positional argument
   *       @Opt(help = "the configuration file")
   *       Path config_file,
   *
   *       // optional argument
   *       // short or long version
   *       //   e.g., -hello or --bind-address
   *       // arguments can be delimited by ' ', '=' or ':'
   *       //   e.g., -hello:192.168.0.10
   *       //   e.g., --bind-address=192.168.0.10
   *       //   e.g., --bind-address 192.168.0.10
   *       @Opt(valueHelp = "address", help = "bind address of the service")
   *       Optional&lt;String&gt; bind_address,
   *
   *       // use enum to restrict possible arguments
   *       @Opt(valueHelp = "level", help = "logger level")
   *       Optional&lt;LogLevel&gt; log_level,
   *
   *       // flag argument
   *       // -v or --verbose
   *       @Opt(help = "logged data verbose mode")
   *       Optional&lt;Boolean&gt; verbose,
   *
   *       // variadic argument
   *       // use a collection like java.util.List or java.util.Set
   *       @Opt(help = "file names exposed as services")
   *       List&lt;String&gt; filenames
   *     ) {}
   * </pre>
   *
   * A call to {@code toHelp("myapp")} return the following text
   * <pre>
   *  myapp <config-file> [options] <filenames...>
   *    with:
   *      config-file: the configuration file
   *      filenames: file names exposed as services
   *
   *    options:
   *       bind-address: bind address of the service
   *         -hello address or --bind-address address
   *       log-level: logger level
   *         -l level or --log-level level
   *       verbose: logged data verbose mode
   *         -v or --verbose
   * </pre>
   *
   * @param applicationName the name of the application
   * @return a text describing the command line arguments
   */
  public String toHelp(String applicationName) {
    var positionals = positionalArgs.stream().map(arg -> "<" + arg.info.name + ">").collect(joining(" "));
    var positionalHelp = positionalArgs.stream().map(arg -> (arg.info.name + ": " + arg.info.help).indent(4)).collect(joining("\n"));
    var options = optionalArgs.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getValue().info.name))
        .collect(groupingBy(Map.Entry::getValue, LinkedHashMap::new, mapping(Map.Entry::getKey, toList())));
    var optionalHelp = options.entrySet().stream()
        .map(e -> e.getKey().info.description().indent(4) + e.getValue().stream().map(optionName -> e.getKey().withParam(optionName)).collect(joining(" or ")).indent(6))
        .collect(joining(""));
    var variadic = Optional.ofNullable(variadicArg).map(arg -> "<" + arg.info.name + "...>");
    var variadicHelp = Optional.ofNullable(variadicArg).map(arg -> arg.info.description().indent(4));
    return applicationName + " " +
        positionals + (optionalHelp.isEmpty()? " ": " [options] ") + variadic.orElse("") + "\n" +
        "  with:\n" +
        positionalHelp +
        variadicHelp.orElse("") +
        Optional.of(optionalHelp).filter(not(String::isEmpty)).map(s -> "\n  options:\n" + s).orElse("");
  }

  private static String[] splitIfCompact(String arg) {
    for(var i = 0; i < arg.length(); i++) {
      var c = arg.charAt(i);
      if (c == ':' || c == '=') {
        return new String[]{ arg.substring(0, i), arg.substring(i + 1) };
      }
    }
    return null;
  }

  /**
   * Exception thrown if the arguments of a command line do not follow the meta description.
   */
  public static class ArgumentParsingException extends RuntimeException {
    private static final long serialVersionUID = 7615300674655629536L;

    public ArgumentParsingException(String message) {
      super(message);
    }

    public ArgumentParsingException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Parse some command line arguments using the meta description of the current {@code ArgVester}.
   * The value of the arguments are converted to the argument type and
   * the value of an optional arguments not present is {@link Optional#empty()}.
   *
   * @param args the command line arguments
   * @return a record containing the value of the arguments
   * @throws ArgumentParsingException if the command line arguments do not follow the meta description
   */
  public R parse(String[] args) throws ArgumentParsingException {
    requireNonNull(args);

    var values = new Object[constructor.getParameterCount()];
    for (var optionalArg : optionalArgs.values()) {
      values[optionalArg.position] = Optional.empty();
    }

    var positionalIndex = 0;
    var variadicList = (variadicArg == null)? null: new ArrayList<>();
    for(var i = 0; i < args.length; i++) {
      var arg = args[i];
      if (arg.startsWith("-")) {  // optional
        OptionalArg optionalArg;
        String value;
        var tokens = splitIfCompact(arg);
        if (tokens != null) {
          optionalArg = optionalArgs.get(tokens[0]);
          value = tokens[1];
        } else {
          optionalArg = optionalArgs.get(arg);
          if (optionalArg == null || optionalArg.flag) {  // delay null check see below
            value = "true";
          } else {
            value = args[++i];
          }
        }
        if (optionalArg == null) {  // optional argument not found
          throw new ArgumentParsingException("unknown optional argument " + arg);
        }
        values[optionalArg.position] = Optional.of(optionalArg.convert(value));
        continue;
      }
      if (positionalIndex < positionalArgs.size()) {  // positional
        var positionalArg = positionalArgs.get(positionalIndex);
        values[positionalIndex++] = positionalArg.convert(arg);
        continue;
      }
      if (variadicList != null) {  // variadic
        variadicList.add(variadicArg.convert(arg));
        continue;
      }

      // error
      throw new ArgumentParsingException("invalid argument " + arg);
    }

    if (positionalIndex != positionalArgs.size()) {
      throw new ArgumentParsingException("required arguments are not provided");
    }

    if (variadicList != null) {
      values[values.length - 1] = variadicList.stream().collect(variadicArg.collector);
    }

    try {
      return constructor.newInstance(values);
    } catch(RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}
