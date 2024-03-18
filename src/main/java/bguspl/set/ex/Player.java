package bguspl.set.ex;

import bguspl.set.Env;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Random;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;

    private BlockingQueue<Integer> limitedQueue; // מה עדיף להשתמש, add or offer and array or link
    private BlockingQueue<Integer> keys;
    private BlockingQueue<Boolean> isPenalty;
    volatile boolean freeze;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.limitedQueue = new ArrayBlockingQueue<>(env.config.featureSize);
        this.dealer = dealer;
        this.keys = new LinkedBlockingQueue<>(env.config.featureSize);
        this.isPenalty = new LinkedBlockingQueue<>();
        freeze = false;

    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();
        while (!terminate) {
            Integer slot;
            try {
                slot = keys.take();
                if (table.getSlotToCard(slot) == null | limitedQueue.contains(slot)) {
                    table.removeToken(id, slot);
                    limitedQueue.remove(slot);
                } else if (limitedQueue.size() < env.config.featureSize) {
                    table.placeToken(id, slot);
                    if (!table.fail)
                        limitedQueue.put(slot);
                    if (limitedQueue.size() == env.config.featureSize) {
                        freeze = true;
                        isPenalty.clear();
                        dealer.checkset.add(id);
                        boolean pen = isPenalty.take();
                        if (freeze) {
                            if (pen) {
                                penalty();
                            } else {
                                point();
                            }
                        }
                        freeze = false;
                    }
                }

            } catch (InterruptedException e) {
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random random = new Random();
            while (!terminate) {
                int randslot = random.nextInt(env.config.tableSize) + 0;
                keyPressed(randslot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        if (!human)
            aiThread.interrupt();
        playerThread.interrupt();
        try {
            playerThread.join();
        } catch (InterruptedException e) {
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        try {
            if (!freeze & table.getSlotToCard(slot) != null & !dealer.isreshuff)
                keys.put(slot);
        } catch (InterruptedException e) {
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        try {
            for (long time = env.config.pointFreezeMillis; time >= 1000; time -= 1000) {
                env.ui.setFreeze(id, time);
                Thread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException ignore) {
        }

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        try {
            for (long time = env.config.penaltyFreezeMillis; time >= 1000; time -= 1000) {
                env.ui.setFreeze(id, time);
                Thread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException ignore) {
        }
    }

    public int score() {
        return score;
    }

    public BlockingQueue<Integer> getLimitedQueue() {
        return limitedQueue;

    }

    public void setIsPenalty(boolean add) {
        try {
            isPenalty.put(add);
        } catch (InterruptedException e) {
        }

    }
}