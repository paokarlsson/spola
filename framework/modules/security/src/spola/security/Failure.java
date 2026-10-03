package spola.security;

/** Why a login failed. */
public enum Failure {
    /**
     * Unknown user, wrong password, or a username or password that cannot be valid. The reasons
     * are deliberately not told apart.
     */
    BAD_CREDENTIALS,
    /** The password was right but the account is disabled. Only given after the right password. */
    DISABLED
}
