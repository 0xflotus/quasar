/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.common.util;

/**
 *
 * @author pron
 */
public interface Function3<S1, S2, S3, T> {
    T apply(S1 x1, S2 x2, S3 x3);
}
