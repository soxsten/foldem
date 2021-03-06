/*
 * This file is part of Fold'em, a Java library for Texas Hold 'em Poker.
 *
 * Fold'em is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Fold'em is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Fold'em.  If not, see <http://www.gnu.org/licenses/>.
 */
package codes.derive.foldem.tool;

import static codes.derive.foldem.Poker.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import codes.derive.foldem.Card;
import codes.derive.foldem.Deck;
import codes.derive.foldem.Hand;
import codes.derive.foldem.Range;
import codes.derive.foldem.board.Board;
import codes.derive.foldem.board.Boards;
import codes.derive.foldem.board.Street;
import codes.derive.foldem.eval.DefaultEvaluator;
import codes.derive.foldem.eval.Evaluator;

/**
 * A type that can be used to calculate equity for hands and ranges using Monte
 * Carlo simulations.
 */
public class EquityCalculationBuilder {

	/* The default sample size to use for simulations. */
	public static final int DEFAULT_SAMPLE_SIZE = 25000;

	/* The default evaluator to use for simulations. */
	public static final Evaluator DEFAULT_EVALUATOR = new DefaultEvaluator();

	/* A list containing cards to remove from the deck during calculations. */
	private final List<Card> dead = new ArrayList<>();

	/* The base board to use during calculations. */
	private Board board = Boards.board();

	/* The sample size to use for simulations. */
	private int sampleSize = DEFAULT_SAMPLE_SIZE;

	/* The evaluator to use for simulations. */
	private Evaluator evaluator = DEFAULT_EVALUATOR;

	/**
	 * Performs an equity calculation for the specified hands and returns a map
	 * containing each hand mapped to its calculated equity.
	 * 
	 * @param hands
	 *            The hands to calculate equity for.
	 * @return A map containing the specified hands mapped to their calculated
	 *         equity.
	 */
	public Map<Hand, Equity> calculate(Hand... hands) {

		/*
		 * Create a base map containing our input hands mapped to their
		 * equities.
		 */
		Map<Hand, Equity> equities = createBaseEquityMap(hands);

		/*
		 * We should create a Random context for deck shuffling that uses a hash
		 * of our input for seeding. This allows for output continuity between
		 * calculations.
		 */
		Random random = new Random(Arrays.hashCode(hands));

		/*
		 * Run our simulations.
		 */
		IntStream.range(0, sampleSize).parallel().forEach((i) -> {
			simulate(equities, random);
		});

		/*
		 * Now just call complete() on all of our generated equities to convert
		 * them to the correct decimal format, and we're done.
		 */
		for (Equity equity : equities.values()) {
			equity.complete();
		}
		return equities;
	}

	/**
	 * Performs an equity calculation for the specified ranges and returns a map
	 * containing each range mapped to its calculated equity.
	 * 
	 * @param ranges
	 *            The ranges to calculate equity for.
	 * @return A map containing the specified ranges mapped to their calculated
	 *         equity.
	 */
	public Map<Range, Equity> calculate(Range... ranges) {

		/*
		 * It may be possible that two or more ranges can have a card in common
		 * in all of their hands or with the board, making it impossible to deal
		 * for a sample.
		 */
		List<Range> unchecked = new CopyOnWriteArrayList<>();
		for (Range range : ranges) {
			unchecked.add(range);
		}
		for (Range a : unchecked) {
			
			/*
			 * Check range a with all other unchecked raises.
			 */
			for (Range b : unchecked) {
				boolean usable = false;
				if (a.equals(b)) {
					continue;
				}

				/*
				 * Combine hands from our ranges.
				 */
				List<Hand> combined = new ArrayList<>();
				combined.addAll(a.all());
				combined.addAll(b.all());

				/*
				 * If range a and b have no hand without a card in common then
				 * they are not usable together.
				 */
				cards: for (Card card : cards()) {
					for (Hand hand : combined) {
						if (!hand.cards().contains(card)) {
							usable = true;
							break cards;
						}
					}
				}
				if (!usable) {
					throw new IllegalArgumentException(
							"These ranges cannot be used beause all hands in one or more of them have a card in common");
				}
			}
			
			/*
			 * Also make sure we have a hand available with no card that is
			 * on the preset board.
			 */
			boolean usable = false;
			for (Hand hand : a.all()) {
				if (Collections.disjoint(hand.cards(), board.cards())) {
					usable = true;
					break;
				}
			}
			if (!usable) {
				throw new IllegalArgumentException(
						"A provided range does not have any hands with cards that are not the set board");
			}

			/*
			 * We can now remove our range from the list of unchecked ranges.
			 */
			unchecked.remove(a);
			if (unchecked.size() == 1) {
				break;
			}
		}

		/*
		 * Create a base map containing our input ranges mapped to their
		 * equities.
		 */
		Map<Range, Equity> equities = createBaseEquityMap(ranges);

		/*
		 * Create a Random context for deck shuffling that uses a hash of our
		 * input for seeding. This allows for output continuity between
		 * calculations.
		 */
		Random random = new Random(Arrays.hashCode(ranges));

		/*
		 * Run our simulations.
		 */
		IntStream.range(0, sampleSize).parallel().forEach((i) -> {
			
			/*
			 * First we should sample our hands from our ranges, making sure
			 * there are no collisions. We'll map them to their respective
			 * equities.
			 */
			Map<Hand, Equity> hands = new HashMap<>();
			while (hands.size() < equities.size()) {
				for (Range range : ranges) {
					Hand sampled = range.sample(random);
					
					/*
					 * If there are any collisions we cannot use the hand.
					 */
					boolean collision = false;
					for (Hand hand : hands.keySet()) {
						
						/*
						 * Check for collisions with dead cards.
						 */
						for (Card card : dead) {
							if (hand.cards().contains(card)) {
								collision = true;
								break;
							}
						}
						
						/*
						 * Check for collisions with other hands.
						 */
						if (!Collections.disjoint(hand.cards(), sampled.cards())) {
							collision = true;
							break;
						}
						
						/*
						 * Check for a collision with the board.
						 */
						if (!Collections.disjoint(board.cards(), hand.cards())) {
							collision = true;
							break;
						}
					}
					
					/*
					 * If there was no collision we can our sampled hand to the map.
					 */
					if (!collision) {
						hands.put(sampled, equities.get(range));
					}
				}
			}
			
			
			/*
			 * Run the simulation.
			 */
			simulate(hands, random);
		});
		
		/*
		 * Now just call complete() on all of our generated equities to convert
		 * them to the correct decimal format, and we're done.
		 */
		for (Equity equity : equities.values()) {
			equity.complete();
		}
		return equities;
	}

	/**
	 * Sets the number of boards to simulate for equity calculations. By default
	 * this value is specified by
	 * {@link EquityCalculationBuilder#DEFAULT_SAMPLE_SIZE}.
	 * 
	 * @param sampleSize
	 *            The number of boards to simulate for equity calculations.
	 * @return The {@link EquityCalculationBuilder} instance, for chaining.
	 */
	public EquityCalculationBuilder useSampleSize(int sampleSize) {
		this.sampleSize = sampleSize;
		return this;
	}

	/**
	 * Sets the {@link Evaluator} to be used to evaluate hand values during
	 * simulations. By default this value is specified by
	 * {@link EquityCalculationBuilder#DEFAULT_EVALUATOR}.
	 * 
	 * @param evaluator
	 *            The evaluator to be used to evaluate hands during simulations.
	 * @return The {@link EquityCalculationBuilder} instance, for chaining.
	 */
	public EquityCalculationBuilder useEvaluator(Evaluator evaluator) {
		this.evaluator = evaluator;
		return this;
	}

	/**
	 * Makes the calculator remove the specified cards from the deck during
	 * calculations.
	 * 
	 * @param cards
	 *            The cards to be removed from the deck.
	 * @return The {@link EquityCalculationBuilder} instance, for chaining.
	 */
	public EquityCalculationBuilder useDeadCards(Card... cards) {
		for (Card card : cards) {
			dead.add(card);
		}
		return this;
	}

	/**
	 * Makes the calculator use the specified {@link Board} during calculations.
	 * 
	 * @param board
	 *            The board to use during calculations.
	 * @return The {@link EquityCalculationBuilder} instance, for chaining.
	 */
	public EquityCalculationBuilder useBoard(Board board) {
		this.board = board;
		return this;
	}

	/**
	 * Creates a map containing empty equities as values mapped to the entries
	 * of the specified array.
	 * 
	 * @param data
	 *            The array whose contents should be used as keys for equities.
	 * 
	 * @return A map containing the contents of the array mapped to new
	 *         equities.
	 */
	private <T> Map<T, Equity> createBaseEquityMap(T[] data) {
		HashMap<T, Equity> equities = new HashMap<>();
		for (T t : data) {
			equities.put(t, new Equity());
		}
		return equities;
	}

	/**
	 * Simulates a river and evaluates each of the specified hands on it. The
	 * result will be applied to each hand's respective equity.
	 * 
	 * @param equities The hands mapped to their respective equities.
	 * 
	 * @param random The {@link java.util.Random } context to use to shuffle the
	 * deck.
	 */
	private void simulate(Map<Hand, Equity> equities, Random random) {

		/*
		 * Create a randomized deck.
		 */
		Deck deck = deck().shuffle(random);

		/*
		 * Deal out the hands being used during the simulation, as well as our
		 * dead cards.
		 */
		for (Hand hand : equities.keySet()) {
			deck.pop(hand);
		}
		for (Card card : dead) {
			deck.pop(card);
		}
		for (Card card : this.board.cards()) {
			deck.pop(card);
		}
		
		/*
		 * Generate a random river using our shuffled deck for our hands to be
		 * evaluated on.
		 */
		Board board = Boards.convert(this.board, Street.RIVER, deck);

		/*
		 * Evaluate our input hands our newly created board to it to see who the
		 * winners were.
		 */
		List<Hand> best = new LinkedList<>();
		int currentBest = Integer.MAX_VALUE;
		for (Hand hand : equities.keySet()) {

			// see if this hand is the best one so far
			int rank = evaluator.rank(hand, board);
			if (rank < currentBest) {

				// clear the previous best hands and add this one
				best.clear();
				best.add(hand);

				// update our current best ranking
				currentBest = rank;
			} else if (rank == currentBest) {
				best.add(hand);
			}
		}

		/*
		 * Finally, apply our evaluation results to the sample.
		 */
		for (Hand hand : equities.keySet()) {
			if (!best.contains(hand)) {
				equities.get(hand).lose += 1;
			}
		}
		if (best.size() > 1) {
			for (Hand hand : best) {
				equities.get(hand).split += 1;
			}
		} else {
			equities.get(best.get(0)).win += 1;
		}
	}

	/**
	 * Represents a hand's equity against one or more other hands.
	 */
	public class Equity {

		/*
		 * Win, loss, and split decimals. These will not be set to their correct
		 * values until right at the very end of calculation, after a call to
		 * complete().
		 */
		private double win = 0.0, lose = 0.0, split = 0.0;

		/* No external instantiation. */
		private Equity() { }

		/**
		 * Obtains how often the hand or hand group associated with this equity
		 * will win, as a decimal.
		 * 
		 * @return How often the hand or hand group associated with this equity
		 *         will win, as a decimal.
		 */
		public double win() {
			return win;
		}

		/**
		 * Obtains how often the hand or hand group associated with this equity
		 * will lose, as a decimal.
		 * 
		 * @return How often the hand or hand group associated with this equity
		 *         will lose, as a decimal.
		 */
		public double lose() {
			return lose;
		}

		/**
		 * Obtains how often the hand or hand group associated with this equity
		 * will split the pot, as a decimal.
		 * 
		 * @return How often the hand or hand group associated with this equity
		 *         will split the pot, as a decimal.
		 */
		public double split() {
			return split;
		}

		/**
		 * Completes the {@link Equity} object by dividing the win/lose/split
		 * numbers by the sample size to create a decimal average of each.
		 */
		private void complete() {
			this.win /= sampleSize;
			this.lose /= sampleSize;
			this.split /= sampleSize;
		}

		@Override
		public String toString() {
			return new StringBuilder().append("[win=").append(win)
					.append(" lose=").append(lose).append(" split=")
					.append(split).append("]").toString();
		}

	}

}
