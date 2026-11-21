package linefollowersim;

/** A control whose value can be written to and read back from a workspace file. */
interface Saveable extends Resettable {

    /** The control's label (made unique with its tab name by ResetGroup). */
    String label();

    /** The current value as text. */
    String value();

    /** Set the value from text (and run the control's action, as if the user had changed it). */
    void restore(String v);
}
