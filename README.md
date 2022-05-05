# argvester
ARGument harVESTER - a simple command line parser written with Love and Java 15

Apart from being fun, the code also show how record (Java 14) and sealed types (Java 15) works together.

The argvester uses a record as a meta description of the arguments of the command line.
The original idea comes from [structopt](https://github.com/p-ranav/structopt) that uses
a struct in C++, so the argvester is a kind of liberal port of structopt in Java.

Argvester supports 3 kinds of arguments
- positional argument, argument that are required and in the right order
- optional argument, argument that starts with '-' or '--' and can happear anywhere on the command line
- variadic argument, one argument at the end that will collect all the arguments that rest

To define the arguments, the ArgVester uses the record components of a record as meta description. 
If a record component is annotated with @Opt, the argument kind is determined by the property
`Opt.kind()`. If the kind is AUTO, then the following algorithm is used
- if the last record component is a collection, it's a variadic argument
- if a record component is typed by Optional or a collection, it's an optional argument
- otherwise it's a positional argument

Moreover, you can use (it's not a requirement) the annotation Opt to specify additional properties.
This annotation is heavily inspired by the project [Google Options](https://github.com/pcj/google-options).

### A record as a meta description 

First, let us define a record that describes all the arguments of the command line

```java
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
    //   e.g., --log-level error
    Optional<LogLevel> log_level,
 
    // optional flag argument
    //   e.g, -v or --verbose
    Optional<Boolean> verbose,
 
    // variadic argument
    // use a collection like java.util.List or java.util.Set
    List<String> filenames
  ) {}
```

Then to create an ArgVester on it
```java
  var argVester = ArgVester.create(MethodHandles.lookup(), Option.class);  
```

and use the ArgVester to parse the command line
```java
  var option = argVester.parse(args);
```
the ArgVester will create an instance of the record populated with the value of the arguments.

