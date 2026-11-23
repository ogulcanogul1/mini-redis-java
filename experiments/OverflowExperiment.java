/**
 * Single-file experiment (JEP 330): contrasts silent two's-complement wrap-around overflow in
 * Java ({@code Long.MAX_VALUE + 1}) with {@link Math#addExact(long, long)}, which {@code INCR} /
 * {@code INCRBY} rely on.
 */
public final class OverflowExperiment {

    public static void main(String[] args) {
        long max = Long.MAX_VALUE;
        long wrapped = max + 1;
        System.out.println("silent overflow: Long.MAX_VALUE + 1 = " + wrapped);

        try {
            Math.addExact(max, 1);
        } catch (ArithmeticException e) {
            System.out.println("Math.addExact(Long.MAX_VALUE, 1) threw ArithmeticException: " + e.getMessage());
        }

        long min = Long.MIN_VALUE;
        try {
            Math.negateExact(min);
        } catch (ArithmeticException e) {
            System.out.println("Math.negateExact(Long.MIN_VALUE) threw ArithmeticException: " + e.getMessage());
        }
    }
}
