*   Deprecate `rails notes` subcommands in favor of passing an `annotations` argument to `rails notes`.

    The following subcommands are replaced by passing `--annotations` or `-a` to `rails notes`:
        - `rails notes:custom ANNOTATION=custom` is deprecated in favor of using `rails notes -a custom`.
        - `rails notes:optimize` is deprecated in favor of using `rails notes -a OPTIMIZE`.
        - `rails notes:todo` is deprecated in favor of  using`rails notes -a TODO`.
        - `rails notes:fixme` is deprecated in favor of using `rails notes -a FIXME`.

    *Annie-Claude Côté*

*   Deprecate `SOURCE_ANNOTATION_DIRECTORIES` environment variable used by `rails notes`
    through `Rails::SourceAnnotationExtractor::Annotation` in favor of using `config.annotations.register_directories`.

    *Annie-Claude Côté*

*   Deprecate `rake notes` in favor of `rails notes`.

    *Annie-Claude Côté*

*   Don't generate unused files in `app:update` task

     Skip the assets' initializer when sprockets isn't loaded.

     Skip `config/spring.rb` when spring isn't loaded.

     Skip yarn's contents when yarn integration isn't used.

    *Tsukuru Tanimichi*

*   Make the master.key file read-only for the owner upon generation on
    POSIX-compliant systems.

    Previously:

        $ ls -l config/master.key
        -rw-r--r--   1 owner  group      32 Jan 1 00:00 master.key

    Now:

        $ ls -l config/master.key
        -rw-------   1 owner  group      32 Jan 1 00:00 master.key

    Fixes #32604.

    *Jose Luis Duran*

*   Deprecate support for using the `HOST` environment to specify the server IP.

    The `BINDING` environment should be used instead.

    Fixes #29516.

    *Yuji Yaginuma*

*   Deprecate passing Rack server name as a regular argument to `rails server`.

    Previously:

        $ bin/rails server thin

    There wasn't an explicit option for the Rack server to use, now we have the
    `--using` option with the `-u` short switch.

    Now:

        $ bin/rails server -u thin

    This change also improves the error message if a missing or mistyped rack
    server is given.

    *Genadi Samokovarov*

*   Add "rails routes --expanded" option to output routes in expanded mode like
    "psql --expanded". Result looks like:

    ```
    $ rails routes --expanded
    --[ Route 1 ]------------------------------------------------------------
    Prefix            | high_scores
    Verb              | GET
    URI               | /high_scores(.:format)
    Controller#Action | high_scores#index
    --[ Route 2 ]------------------------------------------------------------
    Prefix            | new_high_score
    Verb              | GET
    URI               | /high_scores/new(.:format)
    Controller#Action | high_scores#new
    ```

    *Benoit Tigeot*

*   Rails 6 requires Ruby 2.4.1 or newer.

    *Jeremy Daer*


Please check [5-2-stable](https://github.com/rails/rails/blob/5-2-stable/railties/CHANGELOG.md) for previous changes.
