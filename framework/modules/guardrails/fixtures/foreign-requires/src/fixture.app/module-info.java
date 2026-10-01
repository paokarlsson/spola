// Fixture: requires a module that is neither a JDK module nor a project module.
// java.desktop and java.compiler are JDK modules and must not be reported.
module fixture.app {
    requires java.desktop;
    requires static java.compiler;
    requires transitive foo.bar;
}
