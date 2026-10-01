#!/usr/bin/env -S java --source 25 --class-path lib/inject.jar
// Fixture: a launcher script that needs a class path cannot be attributed without it.
import org.example.Injector;

class Unresolved {
    public static void main(String[] args) {
        Injector.run();
    }
}
