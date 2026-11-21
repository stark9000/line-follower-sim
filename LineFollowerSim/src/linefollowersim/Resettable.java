package linefollowersim;

/** Anything that can put itself back to its default value. */
@FunctionalInterface
public interface Resettable {

    void resetDefault();
}
