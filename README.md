# argvester
ARGument harVESTER - a simple command line parser written with Love and Java 15

Apart from being fun, the code also show how record (Java 14) and sealed types (Java 15) works together.

The idea: use a record as a meta description of the arguments of the command line
Argvester supports 3 kinds of arguments
- positional argument, argument that are required and in the right order
- optional argument, argument that starts with '-' or '--' and can happear anywhere on the command line
- variadic argument, one argument at the end that will collect all the arguments that rest


### A record as a meta desciption 

The idea is to create a record that describes all the arguments of the command line

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

