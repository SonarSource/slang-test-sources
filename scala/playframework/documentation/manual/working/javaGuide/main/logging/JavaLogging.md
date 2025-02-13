<!--- Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com> -->
# The Logging API

Using logging in your application can be useful for monitoring, debugging, error tracking, and business intelligence. Play provides an API for logging which is accessed through the [`Logger`](api/java/play/Logger.html) class and uses [Logback](https://logback.qos.ch/) as the default logging engine.

## Logging architecture

The logging API uses a set of components that help you to implement an effective logging strategy.

#### Logger

Your application can define loggers to send log message requests. Each logger has a name which will appear in log messages and is used for configuration.  

Loggers follow a hierarchical inheritance structure based on their naming. A logger is said to be an ancestor of another logger if its name followed by a dot is the prefix of descendant logger name. For example, a logger named "com.foo" is the ancestor of a logger named "com.foo.bar.Baz." All loggers inherit from a root logger. Logger inheritance allows you to configure a set of loggers by configuring a common ancestor.

Play applications are provided a default logger named "application" or you can create your own loggers. The Play libraries use a logger named "play", and some third party libraries will have loggers with their own names.

#### Log levels

Log levels are used to classify the severity of log messages. When you write a log request statement you will specify the severity and this will appear in generated log messages.

This is the set of available log levels, in decreasing order of severity.

- `OFF` - Used to turn off logging, not as a message classification.
- `ERROR` - Runtime errors, or unexpected conditions.
- `WARN` - Use of deprecated APIs, poor use of API, 'almost' errors, other runtime situations that are undesirable or unexpected, but not necessarily "wrong".
- `INFO` - Interesting runtime events such as application startup and shutdown.
- `DEBUG` - Detailed information on the flow through the system.
- `TRACE` - Most detailed information.

In addition to classifying messages, log levels are used to configure severity thresholds on loggers and appenders. For example, a logger set to level `INFO` will log any request of level `INFO` or higher (`INFO`, `WARN`, `ERROR`) but will ignore requests of lower severities (`DEBUG`, `TRACE`). Using `OFF` will ignore all log requests.

#### Appenders

The logging API allows logging requests to print to one or many output destinations called "appenders." Appenders are specified in configuration and options exist for the console, files, databases, and other outputs.

Appenders combined with loggers can help you route and filter log messages. For example, you could use one appender for a logger that logs useful data for analytics and another appender for errors that is monitored by an operations team.

> **Note:** For further information on architecture, see the [Logback documentation](https://logback.qos.ch/manual/architecture.html).

## Using Loggers

First import the [`Logger`](api/java/play/Logger.html) class:

@[logging-import](code/javaguide/logging/JavaLogging.java)

### The default Logger

The [`Logger`](api/java/play/Logger.html) class serves as the default logger using the name "application." You can use it to write log request statements:

@[logging-default-logger](code/javaguide/logging/JavaLogging.java)

Using Play's default logging configuration, these statements will produce console output similar to this:

```
[debug] application - Attempting risky calculation.
[error] application - Exception with riskyCalculation
java.lang.ArithmeticException: / by zero
    at controllers.Application.riskyCalculation(Application.java:20) ~[classes/:na]
    at controllers.Application.index(Application.java:11) ~[classes/:na]
    at Routes$$anonfun$routes$1$$anonfun$applyOrElse$1$$anonfun$apply$1.apply(routes_routing.scala:69) [classes/:na]
    at Routes$$anonfun$routes$1$$anonfun$applyOrElse$1$$anonfun$apply$1.apply(routes_routing.scala:69) [classes/:na]
    at play.core.Router$HandlerInvoker$$anon$8$$anon$2.invocation(Router.scala:203) [play_2.10-2.3-M1.jar:2.3-M1]
```

Note that the messages have the log level, logger name, message, and stack trace if a Throwable was used in the log request.

### Creating your own loggers

Although it may be tempting to use the default logger everywhere, it's generally a bad design practice. Creating your own loggers with distinct names allows for flexible configuration, filtering of log output, and pinpointing the source of log messages.

You can create a new logger using the `Logger.of` factory method with a name argument:

@[logging-create-logger-name](code/javaguide/logging/JavaLogging.java)

A common strategy for logging application events is to use a distinct logger per class using the class name. The logging API supports this with a factory method that takes a class argument:

@[logging-create-logger-class](code/javaguide/logging/JavaLogging.java)

### Using Markers

The SLF4J API has a concept of markers, which act to enrich logging messages and mark out messages as being of special interest.  Markers are especially useful for triggering and filtering -- for example, [OnMarkerEvaluator](https://logback.qos.ch/manual/appenders.html#OnMarkerEvaluator) can send an email when a marker is seen, or particular flows can be marked out to their own appenders.

Markers can be extremely useful, because they can carry extra contextual information for loggers.  For example, using [Logstash Logback Encoder](https://github.com/logstash/logstash-logback-encoder#loggingevent_custom_event), request information can be encoded into logging statements automatically:

@[logging-log-info-with-request-context](code/javaguide/logging/JavaMarkerController.java)

Note that the `requestMarker` method depends on having an `Http.Context` thread local variable in scope, so if you are using [[asynchronous code|JavaAsync]] you must specify an [`HttpExecutionContext`](api/java/play/libs/concurrent/HttpExecutionContext.html):

@[logging-log-info-with-async-request-context](code/javaguide/logging/JavaMarkerController.java)

Note that markers are also very useful for "tracer bullet" style logging, where you want to log on a specific request without explicitly changing log levels.  For example, you can add a marker only when certain conditions are met:

@[logging-log-trace-with-tracer-controller](code/javaguide/logging/JavaTracerController.java)

And then trigger logging with the following TurboFilter in `logback.xml`:

```xml
<turboFilter class="ch.qos.logback.classic.turbo.MarkerFilter">
  <Name>TRACER_FILTER</Name>
  <Marker>TRACER</Marker>
  <OnMatch>ACCEPT</OnMatch>
</turboFilter>
```

At which point you can dynamically set debug statements in response to input.

For more information about using Markers in logging, see [TurboFilters](https://logback.qos.ch/manual/filters.html#TurboFilter) and [marker based triggering](https://logback.qos.ch/manual/appenders.html#OnMarkerEvaluator) sections in the Logback manual.

### Logging patterns

Effective use of loggers can help you achieve many goals with the same tool:

@[logging-pattern-mix](code/javaguide/logging/Application.java)

This example uses [[action composition|JavaActionsComposition]] to define an `AccessLoggingAction` that will log request data to a logger named "access." The `Application` controller uses this action and it also uses its own logger (named after its class) for application events. In configuration you could then route these loggers to different appenders, such as an access log and an application log.

The above design works well if you want to log request data for only specific actions. To log all requests, it's better to use a [[filter|JavaHttpFilters]] or extend [`play.http.DefaultHttpRequestHandler`](api/java/play/http/DefaultHttpRequestHandler.html).

## Configuration

See [[configuring logging|SettingsLogger]] for details on configuration.
