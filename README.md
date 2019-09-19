# scala-cross-maven-plugin

This plugin enables you to publish "clean" POM files after interpolating
values for the Scala version and Scala binary version.

## Why

A common way to vary Scala binary versions is to use Maven profiles,
declaring one profile for each binary version you want to cross compile
to.  Then you run Maven once for each binary version, activating the
appropriate profile, to build and deploy artifacts.

However, the published POMs end up not working so well:

* Maven doesn't like it when you use variable substitution in artifact
  IDs (and will warn you of this on startup).
* The published POMs will require consumers to know about (and
  duplicate) the use of your profile IDs in order to interpolate the
  correct variables.
* Many developers set one of the Scala-version profiles to be activated
  by default, which:
  * can cause confusing (silent) failures if a new developer runs Maven
    without explicitly specifying the desired profile.
  * can cause confusing issues if the set of property names in each
    Scala-version profile is not exactly the same.
* If one of the Scala-version profiles is _not_ set as activated by
  default, often default values for the Scala version and binary/compat
  version are specified outside the profile, which can cause similar
  problems to those outlined in the previous point.

### Other Resources / Prior Art

* There's a fine discussion of the perils of using Maven for Scala
  builds [by Ryan Williams of Hammer
  Lab](http://www.hammerlab.org/2017/04/06/scala-build-tools/).
* The
  [flatten-maven-plugin](https://www.mojohaus.org/flatten-maven-plugin/)
  almost does what we want, but is difficult to configure so that it
  makes the fewest modifications necessary to the POM file.

## Usage

To make this work, ideally your POM and build should conform to a
convention like so:

* You have a separate profile for each Scala binary/compat version,
  named like `scala-2.11`, `scala-2.12`, etc.
* None of these `scala-*` profiles are active by default.
* You use properties named `scala.version` and `scala.binary.version`
  (or `scala.compat.version`) to determine which Scala versions to use.
* You have artifact (and possibly group) IDs that require the
  binary/compat version to be interpolated into them.
* You do _not_ put defaults for the above properties in the POM's
  main properties section.  (Note: Some IDEs, like IntelliJ IDEA, won't
  work properly without a default for `scala.binary.version`, even if
  you properly select the correct profile to use.  Adding a default for
  that property seems to work ok.)

If you've fulfilled the above, you can just do the following:

```xml
<build>
  <plugins>
    ..
    <plugin>
      <groupId>org.spurint.maven.plugins</groupId>
      <artifactId>scala-cross-maven-plugin</artifactId>
      <version>{see tags for latest version}</version>
      <executions>
          <execution>
              <id>rewrite-pom</id>
              <goals>
                  <goal>rewrite-pom</goal>
              </goals>
          </execution>
      </executions>
    </plugin>
    ..
  </plugins>
</build>
```

When you run your build, pass `-Pscala-2.12` (or whichever profile you
want) to Maven, and things will just work.

In reality, the plugin will take _any_ properties defined in your
Scala-version profile and interpolate them into group and artifact IDs
in the rest of the POM.

For reference, here's an example of some profiles you might use:

```xml
<profiles>
  <profile>
    <id>scala-2.11</id>
    <properties>
      <scala.binary.version>2.11</scala.binary.version>
      <scala.version>2.11.12</scala.version>
    </profiles>
  </profile>
  <profile>
    <id>scala-2.12</id>
    <properties>
      <scala.binary.version>2.12</scala.binary.version>
      <scala.version>2.12.10</scala.version>
    </profiles>
  </profile>
</profiles>
```

## Configuration

There are a few settings you can use to tailor execution to your
environment.

| Name | Default | Description |
|:-----|:--------|:------------|
| `rewrittenPomPath` | `${project.build.directory}/.scala-cross-pom.xml` | Full path to where to write the interpolated POM file. |
| `scalaProfilePrefix` | `scala-` | Prefix for profile names used to for scala cross-compilation.  The assumed suffix is the Scala binary version. |
| `scalaProfileId` | (none) | Alternatively, you can specify the full name of the profile to use (`scalaProfilePrefix` will be ignored). |
| `scrubProfiles` | `false` | Before writing the final POM, remove all detected Scala-version profiles. |
