package com.bastiaanjansen.otp;

import com.bastiaanjansen.otp.helpers.URIHelper;
import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Generates counter-based one-time passwords
 *
 * @author Bastiaan Jansen
 */
public final class HOTPGenerator {

    private static final String URL_SCHEME = "otpauth";

    /**
     * Default value for password length
     */
    private static final int DEFAULT_PASSWORD_LENGTH = 6;

    /**
     * Default value for HMAC Algorithm
     */
    private static final HMACAlgorithm DEFAULT_HMAC_ALGORITHM = HMACAlgorithm.SHA1;

    private static final String OTP_TYPE = "hotp";

    /**
     * Number of digits for generated code in range 6...8, defaults to 6
     */
    private final int passwordLength;

    /**
     * Hashing algorithm used to generate code, defaults to SHA1
     */
    private final HMACAlgorithm algorithm;

    /**
     * Secret key used to generate the code, this should be a base32 string
     */
    private final byte[] secret;

    private HOTPGenerator(final Builder builder) {
        this.passwordLength = builder.passwordLength;
        this.algorithm = builder.algorithm;
        this.secret = builder.secret;
    }

    /**
     * Build a TOTPGenerator from an OTPAuth URI
     *
     * @param uri OTPAuth URI
     * @return HOTP
     * @throws URISyntaxException when URI cannot be parsed
     */
    public static HOTPGenerator fromURI(final URI uri) throws URISyntaxException {
        Map<String, String> query = URIHelper.queryItems(uri);

        byte[] secret = Optional.ofNullable(query.get(URIHelper.SECRET))
                .map(String::getBytes)
                .orElseThrow(() -> new IllegalArgumentException("Secret query parameter must be set"));

        Builder builder = new Builder(secret);

        try {
            Optional.ofNullable(query.get(URIHelper.DIGITS))
                    .map(Integer::valueOf)
                    .ifPresent(builder::withPasswordLength);
            Optional.ofNullable(query.get(URIHelper.ALGORITHM))
                    .map(String::toUpperCase)
                    .map(HMACAlgorithm::valueOf)
                    .ifPresent(builder::withAlgorithm);
        } catch (Exception e) {
            throw new URISyntaxException(uri.toString(), "URI could not be parsed");
        }

        return builder.build();
    }

    /**
     * Create a com.bastiaanjansen.otp.HOTPGenerator with default values
     *
     * @param secret used to generate hash
     * @return a com.bastiaanjansen.otp.HOTPGenerator with default values
     */
    public static HOTPGenerator withDefaultValues(final byte[] secret) {
        return new HOTPGenerator.Builder(secret).build();
    }

    /**
     * Create an OTPAuth URI for easy user on-boarding with only an issuer
     *
     * @param counter of URI
     * @param issuer name for URI
     * @return OTPAuth URI
     * @throws URISyntaxException when URI cannot be created
     */
    public URI getURI(final int counter, final String issuer) throws URISyntaxException {
        return getURI(counter, issuer, "");
    }

    /**
     * Create an OTPAuth URI for easy user on-boarding with an issuer and account name
     *
     * @param counter of URI
     * @param issuer name for URI
     * @param account name for URI
     * @return OTPAuth URI
     * @throws URISyntaxException when URI cannot be created
     */
    public URI getURI(final int counter, final String issuer, final String account) throws URISyntaxException {
        Map<String, String> query = new HashMap<>();
        query.put(URIHelper.COUNTER, String.valueOf(counter));

        return getURI(OTP_TYPE, issuer, account, query);
    }

    public int getPasswordLength() {
        return passwordLength;
    }

    public HMACAlgorithm getAlgorithm() {
        return algorithm;
    }

    /**
     * Checks whether a code is valid for a specific counter with a delay window of 0
     *
     * @param code    an OTP code
     * @param counter how many times time interval has passed since 1970
     * @return a boolean, true if code is valid, otherwise false
     */
    public boolean verify(final String code, final long counter) {
        return verify(code, counter, 0);
    }

    /**
     * Checks whether a code is valid for a specific counter taking a delay window into account
     *
     * @param code an OTP codee
     * @param counter how many times time interval has passed since 1970
     * @param delayWindow window in which a code can still be deemed valid
     * @return a boolean, true if code is valid, otherwise false
     */
    public boolean verify(final String code, final long counter, final int delayWindow) {
        if (code.length() != passwordLength) return false;

        for (int i = -delayWindow; i <= delayWindow; i++) {
            String currentCode = generate(counter + i);
            if (code.equals(currentCode)) return true;
        }

        return false;
    }

    /**
     * Generate a code
     *
     * @param counter how many times time interval has passed since 1970
     * @return generated OTP code
     * @throws IllegalStateException when hashing algorithm throws an error
     */
    public String generate(final long counter) throws IllegalStateException {
        if (counter < 0)
            throw new IllegalArgumentException("Counter must be greater than or equal to 0");

        byte[] secretBytes = decodeBase32(secret);
        byte[] counterBytes = longToBytes(counter);

        byte[] hash;

        try {
            hash = generateHash(secretBytes, counterBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException();
        }

        return getCodeFromHash(hash);
    }

    /**
     * Generate an OTPAuth URI
     *
     * @param type of OTPAuth URI: totp or hotp
     * @param issuer name for URI
     * @param account name for URI
     * @param query items of URI
     * @return created OTPAuth URI
     * @throws URISyntaxException when URI cannot be created
     */
    URI getURI(final String type, final String issuer, final String account, final Map<String, String> query) throws URISyntaxException {
        query.put(URIHelper.DIGITS, String.valueOf(passwordLength));
        query.put(URIHelper.ALGORITHM, algorithm.name());
        query.put(URIHelper.SECRET, new String(secret, StandardCharsets.UTF_8));
        query.put(URIHelper.ISSUER, issuer);

        String path = account.isEmpty() ? issuer : String.format("%s:%s", issuer, account);

        return URIHelper.createURI(URL_SCHEME, type, path, query);
    }

    /**
     * Decode a base32 value to bytes array
     *
     * @param value base32 value
     * @return bytes array
     */
    private byte[] decodeBase32(final byte[] value) {
        Base32 codec = new Base32();
        return codec.decode(value);
    }

    /**
     * Convert a long value tp bytes array
     *
     * @param value long value
     * @return bytes array
     */
    private byte[] longToBytes(final long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    /**
     * Generate a hash based on an HMAC algorithm and secret
     *
     * @param secret    Base32 string converted to byte array used to generate hash
     * @param data      to hash
     * @return generated hash
     * @throws NoSuchAlgorithmException when algorithm does not exist
     * @throws InvalidKeyException      when secret is invalid
     */
    private byte[] generateHash(final byte[] secret, final byte[] data) throws InvalidKeyException, NoSuchAlgorithmException {
        // Create a secret key with correct SHA algorithm
        SecretKeySpec signKey = new SecretKeySpec(secret, "RAW");
        // Mac is 'message authentication code' algorithm (RFC 2104)
        Mac mac = Mac.getInstance(algorithm.getHMACName());
        mac.init(signKey);
        // Hash data with generated sign key
        return mac.doFinal(data);
    }

    /**
     * Get code from hash with specified password length
     *
     * @param hash
     * @return OTP code
     */
    private String getCodeFromHash(final byte[] hash) {
        /* Find mask to get last 4 digits:
        1. Set all bits to 1: ~0 -> 11111111 -> 255 decimal -> 0xFF
        2. Shift n (in this case 4, because we want the last 4 bits) bits to left with <<
        3. Negate the result: 1111 1100 -> 0000 0011
         */
        int mask = ~(~0 << 4);

        /* Get last 4 bits of hash as offset:
        Use the bitwise AND (&) operator to select last 4 bits
        Mask should be 00001111 = 15 = 0xF
        Last byte of hash & 0xF = last 4 bits:
        Example:
        Input: decimal 219 as binary: 11011011 &
        Mask: decimal 15 as binary:   00001111
        -----------------------------------------
        Output: decimal 11 as binary: 00001011
         */
        byte lastByte = hash[hash.length - 1];
        int offset = lastByte & mask;

        // Get 4 bytes from hash from offset to offset + 3
        byte[] truncatedHashInBytes = { hash[offset], hash[offset + 1], hash[offset + 2], hash[offset + 3] };

        // Wrap in ByteBuffer to convert bytes to long
        ByteBuffer byteBuffer = ByteBuffer.wrap(truncatedHashInBytes);
        long truncatedHash = byteBuffer.getInt();

        // Mask most significant bit
        truncatedHash &= 0x7FFFFFFF;

        // Modulo (%) truncatedHash by 10^passwordLength
        truncatedHash %= Math.pow(10, passwordLength);

        // Left pad with 0s for an n-digit code
        return String.format("%0" + passwordLength + "d", truncatedHash);
    }

    /**
     * @author Bastiaan Jansen
     * @see HOTPGenerator
     */
    public static final class Builder {

        /**
         * Number of digits for generated code in range 6...8, defaults to 6
         */
        private int passwordLength;

        /**
         * Hashing algorithm used to generate code, defaults to SHA1
         */
        private HMACAlgorithm algorithm;

        /**
         * Secret key used to generate the code, this should be a base32 string
         */
        private final byte[] secret;

        public Builder(final byte[] secret) {
            if (secret.length == 0)
                throw new IllegalArgumentException("Secret must not be empty");

            this.secret = secret;
            this.passwordLength = DEFAULT_PASSWORD_LENGTH;
            this.algorithm = DEFAULT_HMAC_ALGORITHM;
        }

        public Builder withPasswordLength(final int passwordLength) {
            if (!passwordLengthIsValid(passwordLength))
                throw new IllegalArgumentException("Password length must be between 6 and 8 digits");

            this.passwordLength = passwordLength;
            return this;
        }

        /**
         * Change hashing algorithm
         *
         * @param algorithm HMAC hashing algorithm
         * @return concrete builder
         */
        public Builder withAlgorithm(final HMACAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        /**
         * Build the generator with specified options
         *
         * @return HOTP
         */
        public HOTPGenerator build() {
            return new HOTPGenerator(this);
        }

        /**
         * Check if password is in range 6...8
         *
         * @param passwordLength number of digits for generated code in range 6...8
         * @return whether password is valid
         */
        private boolean passwordLengthIsValid(final int passwordLength) {
            return passwordLength >= 6 && passwordLength <= 8;
        }
    }
}
