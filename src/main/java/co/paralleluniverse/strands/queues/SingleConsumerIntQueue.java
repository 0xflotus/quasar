/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.strands.queues;

/**
 *
 * @author pron
 */
public interface SingleConsumerIntQueue<Node> {
    boolean enq(int item);
    int intValue(Node node);
}
