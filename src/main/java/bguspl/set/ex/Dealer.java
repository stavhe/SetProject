
package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

	/**
	 * The game environment object.
	 */
	private final Env env;

	/**
	 * Game entities.
	 */
	private final Table table;
	private final Player[] players;

	/**
	 * The list of card ids that are left in the dealer's deck.
	 */
	private final List<Integer> deck;

	/**
	 * True iff game should be terminated.
	 */
	private volatile boolean terminate;

	/**
	 * The time when the dealer needs to reshuffle the deck due to turn timeout.
	 */
	private long reshuffleTime = Long.MAX_VALUE;

	/**
	 * Queue of the players' ids that have set of three cards.
	 */
	public BlockingQueue<Integer> checkset;
	Thread dealerthread;
	private Integer playerId;
	private final Thread[] playersThreads;
	private final List<Integer> index;
	volatile boolean isreshuff;

	public Dealer(Env env, Table table, Player[] players) {
		this.env = env;
		this.table = table;
		this.players = players;
		deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
		checkset = new LinkedBlockingQueue<>();
		playersThreads = new Thread[players.length];
		index = IntStream.range(0, env.config.tableSize).boxed().collect(Collectors.toList());
		playerId = null;
		isreshuff = false;
	}

	/**
	 * The dealer thread starts here (main loop for the dealer thread).
	 */
	@Override
	public void run() {
		dealerthread = Thread.currentThread();
		env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
		for (int i = 0; i < env.config.players; i++) {
			playersThreads[i] = new Thread(players[i], "Player-" + Integer.toString(i + 1));
			playersThreads[i].start();
		}
		isreshuff = true;
		Collections.shuffle(deck);
		while (!shouldFinish()) {
			placeCardsOnTable();
			updateTimerDisplay(true);
			timerLoop();
			updateTimerDisplay(true);
			removeAllCardsFromTable();
		}
		announceWinners();
		terminate();
		env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
	}

	/**
	 * The inner loop of the dealer thread that runs as long as the countdown did
	 * not time out.
	 */
	private void timerLoop() {
		reshuffleTime = env.config.turnTimeoutMillis + System.currentTimeMillis();
		if (env.util.findSets(deck, 1).size() == 0)
			terminate = true;
		while (!terminate && System.currentTimeMillis() < reshuffleTime) {
			sleepUntilWokenOrTimeout();
			updateTimerDisplay(false);
			removeCardsFromTable();
			placeCardsOnTable();
		}
	}

	/**
	 * Called when the game should be terminated.
	 */
	public void terminate() {
		terminate = true;
		for (int i = players.length - 1; i >= 0; i--) {
			players[i].terminate();
		}
		try {
			Thread.sleep(env.config.endGamePauseMillies);
		} catch (InterruptedException ignore) {
		}
		Thread.currentThread().interrupt();
	}

	/**
	 * Check if the game should be terminated or the game end conditions are met.
	 *
	 * @return true iff the game should be finished.
	 */
	private boolean shouldFinish() {
		return terminate || env.util.findSets(deck, 1).size() == 0;
	}

	/**
	 * Checks cards should be removed from the table and removes them.
	 */
	private void removeCardsFromTable() {
		if (playerId != null) {
			int[] check = new int[env.config.featureSize];
			Iterator<Integer> it = players[playerId].getLimitedQueue().iterator();
			int j = 0;
			while (it.hasNext()) {
				Integer m = it.next();
				check[j] = table.getSlotToCard(m);
				j++;
			}
			if (env.util.testSet(check)) {
				while (!players[playerId].getLimitedQueue().isEmpty()) {
					Integer l = players[playerId].getLimitedQueue().remove();
					table.removeCard(l);
					for (int k = 0; k < players.length; k++) {
						if (table.removeToken(players[k].id, l)) {
							if (k != playerId) {
								players[k].getLimitedQueue().remove(l);
								checkset.remove(k);
								players[k].freeze = false;
								players[k].setIsPenalty(true);
							}
						}
					}
				}
				players[playerId].setIsPenalty(false);
				updateTimerDisplay(true);
			} else {
				players[playerId].setIsPenalty(true);
			}
		}
		playerId = null;
	}

	/**
	 * Check if any cards can be removed from the deck and placed on the table.
	 */
	private void placeCardsOnTable() {
		isreshuff = true;
		boolean placed = false;
		Collections.shuffle(index);
		for (int i = 0; i < index.size() & deck.size() > 0; i++) {
			if (table.getSlotToCard(index.get(i)) == null) {
				table.placeCard(deck.remove(0), index.get(i));
				placed = true;
			}
		}
		isreshuff = false;
		if (env.config.hints & placed)
			table.hints();
	}

	/**
	 * Sleep for a fixed amount of time or until the thread is awakened for some
	 * purpose.
	 */
	private void sleepUntilWokenOrTimeout() {
		try {
			if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis) {
				playerId = checkset.poll();
			} else {
				playerId = checkset.poll(1000, TimeUnit.MILLISECONDS);
			}
		} catch (Exception e) {
		}

	}

	/**
	 * Reset and/or update the countdown and the countdown display.
	 */
	private void updateTimerDisplay(boolean reset) {
		if (reset == true) {
			reshuffleTime = env.config.turnTimeoutMillis + System.currentTimeMillis();
			env.ui.setCountdown(env.config.turnTimeoutMillis - 1000, false);
		} else
			env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(),
					(reshuffleTime - System.currentTimeMillis()) < env.config.turnTimeoutWarningMillis);
	}

	/**
	 * Returns all the cards from the table to the deck.
	 */
	private void removeAllCardsFromTable() {
		isreshuff = true;
		env.ui.removeTokens();
		for (int i = 0; i < table.slotToCard.length & deck.size() > 0; i++) {
			if (table.getSlotToCard(i) != null)
				deck.add(table.getSlotToCard(i));
			table.removeCard(i);
		}
		env.ui.removeTokens();
		for (int i = 0; i < players.length; i++) {
			players[i].getLimitedQueue().clear();
		}
		for (int i = 0; i < table.slotToToken.length; i++) {
			table.slotToToken[i].clear();
		}
		checkset.clear();
		for (int i = 0; i < players.length; i++) {
			players[i].freeze = false;
			players[i].setIsPenalty(false);
		}

		Collections.shuffle(deck);
		isreshuff = false;

	}

	/**
	 * Check who is/are the winner/s and displays them.
	 */
	private void announceWinners() {
		int max = players[0].score();
		int count = 1;
		for (int i = 1; i < players.length; i++) {
			if (max < players[i].score()) {
				max = players[i].score();
				count = 1;
			} else {
				if (max == players[i].score())
					count++;
			}
		}
		int[] arr = new int[count];
		int j = 0;
		for (int i = 0; i < players.length; i++) {
			if (max == players[i].score()) {
				arr[j] = players[i].id;
				j++;
			}
		}
		env.ui.announceWinner(arr);
	}
}