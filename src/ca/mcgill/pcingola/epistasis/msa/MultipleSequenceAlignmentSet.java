package ca.mcgill.pcingola.epistasis.msa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import ca.mcgill.mcb.pcingola.collections.AutoHashMap;
import ca.mcgill.mcb.pcingola.fileIterator.LineFileIterator;
import ca.mcgill.mcb.pcingola.interval.Chromosome;
import ca.mcgill.mcb.pcingola.interval.Genome;
import ca.mcgill.mcb.pcingola.interval.Marker;
import ca.mcgill.mcb.pcingola.interval.Markers;
import ca.mcgill.mcb.pcingola.interval.tree.IntervalForest;
import ca.mcgill.mcb.pcingola.util.Gpr;
import ca.mcgill.mcb.pcingola.util.GprSeq;
import ca.mcgill.mcb.pcingola.util.Timer;
import ca.mcgill.mcb.pcingola.util.Tuple;
import ca.mcgill.pcingola.epistasis.pdb.DistanceResult;
import ca.mcgill.pcingola.epistasis.pdb.DistanceResults;
import ca.mcgill.pcingola.epistasis.phylotree.PhylogeneticTree;

/**
 * Load a multiple sequence alignment file (UCSC)
 * E.g. http://hgdownload.soe.ucsc.edu/goldenPath/hg19/multiz100way/alignments/knownCanonical.exonAA.fa.gz
 *
 * @author pcingola
 */
public class MultipleSequenceAlignmentSet implements Iterable<MultipleSequenceAlignment> {

	public static boolean debug = false;
	public static boolean verbose = false;
	public double SHOW_THRESHOLD = 0.99;
	public final int MIN_COUNT_THRESHOLD = 50;
	public final int MIN_SECOND_TOP_BASE_COUNT = 5;

	int numAligns;
	String sequenceAlignmentFile;
	String species[];
	ArrayList<MultipleSequenceAlignment> msas;
	AutoHashMap<String, List<MultipleSequenceAlignment>> msasByTrId;
	HashMap<String, MultipleSequenceAlignment> msaById;
	IntervalForest intForest; // Interval forest (MSAs intervals)

	public MultipleSequenceAlignmentSet(String sequenceAlignmentFile, int numAligns) {
		this.numAligns = numAligns;
		this.sequenceAlignmentFile = sequenceAlignmentFile;
		species = new String[numAligns];
		msas = new ArrayList<MultipleSequenceAlignment>();
		msasByTrId = new AutoHashMap<String, List<MultipleSequenceAlignment>>(new ArrayList<MultipleSequenceAlignment>());
		msaById = new HashMap<String, MultipleSequenceAlignment>();
	}

	public MultipleSequenceAlignmentSet(String sequenceAlignmentFile, PhylogeneticTree tree) {
		this(sequenceAlignmentFile, tree.childNames().size());
	}

	/**
	 * Add entry
	 */
	public boolean add(MultipleSequenceAlignment msa) {
		boolean ok = msas.add(msa);
		msasByTrId.getOrCreate(msa.getTranscriptId()).add(msa);
		msaById.put(msa.getId(), msa);
		return ok;
	}

	/**
	 * Build interval forest using LogLik markers
	 */
	public void buildForest() {
		if (intForest != null) return; // Nothing to do

		Timer.showStdErr("Building interval forest: MSAs");

		// Create a collections of 'markers'
		Genome genome = new Genome();
		Markers markers = new Markers();
		for (MultipleSequenceAlignment msa : this) {
			// Create a marker for this MSA and add it to 'markers'
			Chromosome chromo = genome.getOrCreateChromosome(msa.getChromosomeName());
			Marker m = new Marker(chromo, msa.getStart(), msa.getEnd(), false, msa.getId());
			markers.add(m);
		}

		// Create forest and build
		intForest = new IntervalForest(markers);
		intForest.build();
		Timer.showStdErr("Done. Added " + markers.size() + " markers.");
	}

	public void calcSkip() {
		Timer.showStdErr("Pre-calculating skips.");
		getMsas().parallelStream().forEach(MultipleSequenceAlignment::calcSkip);
	}

	/**
	 * Return '2 * numBases + 1' string representing the column sequences
	 * at position msaId:pos and the surrounding 'numBases' columns
	 */
	public String[] colSequences(String msaId, int pos, int numBases) {
		// Initialize
		String seqs[] = new String[2 * numBases + 1];

		// Find positions 'pos' and after
		MultipleSequenceAlignment msa = getMsa(msaId);
		int maxj = 2 * numBases + 1;
		for (int i = pos, j = numBases; j < maxj; i++, j++) {
			if (i >= msa.getAaSeqLen()) break;
			seqs[j] = msa.getColumnString(i);
		}

		// Find positions before 'pos'
		msa = getMsa(msaId);
		for (int i = pos - 1, j = numBases - 1; j >= 0; i--, j--) {
			if (i < 0) break;
			seqs[j] = msa.getColumnString(i);
		}

		return seqs;
	}

	/**
	 * Count number of amino acids
	 */
	public int[] countAa() {
		int counts[] = new int[GprSeq.AMINO_ACIDS.length];
		forEach(m -> m.countAa(counts));
		return counts;
	}

	/**
	 * Count number of amino acids for a specific alignment
	 */
	public int[] countAa(int alignNum) {
		int counts[] = new int[GprSeq.AMINO_ACIDS.length];
		forEach(m -> m.countAa(alignNum, counts));
		return counts;
	}

	/**
	 * Count number of amino acids
	 */
	public int[] countAaPairs(DistanceResults aaContacts) {
		int counts[] = new int[GprSeq.AMINO_ACIDS.length * GprSeq.AMINO_ACIDS.length];

		aaContacts.stream() //
		.filter(d -> getMsa(d.msa1) != null && getMsa(d.msa2) != null) //
		.forEach(d -> countAaPairs(counts, d)) //
		;

		return counts;
	}

	/**
	 * Count AA pairs
	 */
	void countAaPairs(int counts[], DistanceResult d) {
		MultipleSequenceAlignment msa1 = getMsa(d.msa1);
		MultipleSequenceAlignment msa2 = getMsa(d.msa2);
		int numSeqs = msa1.getNumSeqs();

		for (int i = 0; i < numSeqs; i++) {
			int code = GprSeq.aaPairCode(msa1.getCode(i, d.msaIdx1), msa2.getCode(i, d.msaIdx2));
			if (code >= 0) counts[code]++;
		}
	}

	/**
	 * Count number of transitions between two sequences
	 */
	public int[][] countTransitions(int seqNum1, int seqNum2) {
		int counts[][] = new int[GprSeq.AMINO_ACIDS.length][GprSeq.AMINO_ACIDS.length];
		forEach(m -> m.countTransitions(seqNum1, seqNum2, counts));
		return counts;
	}

	/**
	 * Count AA-Pair transitions from seqNum1 to seqNum2 in all distanceResults
	 */
	public int[][] countTransitionsPairs(int seqNum1, int seqNum2, DistanceResults aaContacts) {
		int n = GprSeq.AMINO_ACIDS.length * GprSeq.AMINO_ACIDS.length;
		int counts[][] = new int[n][n];

		aaContacts.stream() //
		.filter(d -> getMsa(d.msa1) != null && getMsa(d.msa2) != null) //
		.forEach(d -> countTransitionsPairs(counts, seqNum1, seqNum2, d));

		return counts;
	}

	/**
	 * Count AA-Pair transitions from seqNum1 to seqNum2 in distanceResult 'd'
	 */
	void countTransitionsPairs(int counts[][], int seqNum1, int seqNum2, DistanceResult d) {
		MultipleSequenceAlignment msa1 = getMsa(d.msa1);
		MultipleSequenceAlignment msa2 = getMsa(d.msa2);

		int code1 = GprSeq.aaPairCode(msa1.getCode(seqNum1, d.msaIdx1), msa2.getCode(seqNum1, d.msaIdx2));
		if (code1 < 0) return;

		int code2 = GprSeq.aaPairCode(msa1.getCode(seqNum2, d.msaIdx1), msa2.getCode(seqNum2, d.msaIdx2));
		if (code2 < 0) return;

		counts[code1][code2]++;
	}

	/**
	 * Find next AA
	 * @return null if not found
	 */
	public Tuple<MultipleSequenceAlignment, Integer> findNext(MultipleSequenceAlignment msa, int idx) {
		int nextIdx = idx + 1;
		MultipleSequenceAlignment nextMsa = msa;

		if (nextIdx >= msa.getAaSeqLen()) {
			nextMsa = findNextExon(msa);
			nextIdx = 0;
		}

		if (nextMsa == null) return null;

		return new Tuple<MultipleSequenceAlignment, Integer>(nextMsa, nextIdx);
	}

	/**
	 * Find MSA for exon following 'msa'
	 */
	public MultipleSequenceAlignment findNextExon(MultipleSequenceAlignment msa) {
		boolean retNext = false;

		// Find MSA following 'msa', for the same transcriptId
		for (MultipleSequenceAlignment m : getMsasByTrId(msa.getTranscriptId())) {
			if (retNext) return m;
			if (m.getId().equals(msa.getId())) retNext = true;
		}

		return null;
	}

	/**
	 * Find previous AA
	 * @return null if not found
	 */
	public Tuple<MultipleSequenceAlignment, Integer> findPrevious(MultipleSequenceAlignment msa, int idx) {
		int nextIdx = idx - 1;
		MultipleSequenceAlignment nextMsa = msa;

		if (nextIdx < 0) {
			nextMsa = findPreviousExon(msa);
			if (nextMsa == null) return null;
			nextIdx = nextMsa.getAaSeqLen() - 1;
		}

		return new Tuple<MultipleSequenceAlignment, Integer>(nextMsa, nextIdx);
	}

	/**
	 * Find MSA for exon following 'msa'
	 */
	public MultipleSequenceAlignment findPreviousExon(MultipleSequenceAlignment msa) {

		// Find MSA following 'msa', for the same transcriptId
		MultipleSequenceAlignment prev = null;
		for (MultipleSequenceAlignment m : getMsasByTrId(msa.getTranscriptId())) {
			if (m.getId().equals(msa.getId())) return prev;
			prev = m;
		}

		return null;
	}

	public MultipleSequenceAlignment getMsa(String msaId) {
		return msaById.get(msaId);
	}

	public ArrayList<MultipleSequenceAlignment> getMsas() {
		return msas;
	}

	public List<MultipleSequenceAlignment> getMsasByTrId(String trId) {
		return msasByTrId.get(trId);
	}

	/**
	 * List of MSAs by transcript ID, sorted by transcript's strand (5' to 3')
	 */
	public List<MultipleSequenceAlignment> getMsasByTrIdSorted(String trId) {
		ArrayList<MultipleSequenceAlignment> list = new ArrayList<MultipleSequenceAlignment>();
		list.addAll(msasByTrId.get(trId));

		if (list.isEmpty() || list.get(0).isStrandPlus()) Collections.sort(list);
		else Collections.sort(list, Collections.reverseOrder());

		return list;
	}

	public int getNumAligns() {
		return numAligns;
	}

	public String[] getSpecies() {
		return species;
	}

	/**
	 * Total MSA AA length for a given transcript
	 */
	public int getTranscriptLength(String trId) {
		List<MultipleSequenceAlignment> msasTr = getMsasByTrId(trId);

		int len = 0;
		for (MultipleSequenceAlignment msa : msasTr)
			len += msa.getAaSeqLen();

		return len;
	}

	/**
	 * Return a list of (sorted) transcript IDs
	 */
	public Collection<String> getTrIDs() {
		ArrayList<String> trids = new ArrayList<>();
		trids.addAll(msasByTrId.keySet());
		Collections.sort(trids);
		return trids;

	}

	@Override
	public Iterator<MultipleSequenceAlignment> iterator() {
		return msas.iterator();
	}

	/**
	 * Load MSAs from a FASTA file
	 */
	public void load() {
		Timer.showStdErr("Loading MSA from '" + sequenceAlignmentFile + "'");
		LineFileIterator lif = new LineFileIterator(sequenceAlignmentFile);
		Genome genome = new Genome();

		int countAligns = 1;
		while (lif.hasNext()) {
			int seqLen = -1;
			MultipleSequenceAlignment msa = null;

			// Read an alignment of a protein
			for (int i = 0; i < numAligns; i++) {
				String header = lif.next();

				// There might be an extra empty line in the first header
				while (header != null && header.isEmpty())
					header = lif.next();
				if (header == null) break;

				// Is this a header?
				if (!header.startsWith(">")) throw new RuntimeException("Error (file '" + sequenceAlignmentFile + "', line " + lif.getLineNum() + "): Expecting header empty line, got: '" + header + "'");

				if (i == 0) Gpr.showMark(countAligns++, 100);

				// Parse and check species
				String fields[] = header.split("_");
				String transcriptId = fields[0].substring(1) + "_" + fields[1];
				String speciesName = fields[2];
				if (species[i] == null) species[i] = speciesName;
				else if (!speciesName.equals(species[i])) throw new RuntimeException("Error (file '" + sequenceAlignmentFile + "', line " + lif.getLineNum() + "): Expecting species '" + species[i] + "', got: '" + speciesName + "'");

				// Read sequence
				String sequence = lif.next();
				if (sequence == null) break;

				// Check sequence length
				if (seqLen < 0) {
					seqLen = sequence.length();

					// Chr:start=end
					fields = header.split(" ");
					String chrpos = fields[4];
					int idxPos = chrpos.indexOf(':');
					int idxEnd = chrpos.indexOf('-');
					String chr = chrpos.substring(0, idxPos);
					String posStart = chrpos.substring(idxPos + 1, idxEnd);
					String posEnd = chrpos.substring(idxEnd + 1, chrpos.length() - 1);
					boolean strand = (chrpos.charAt(chrpos.length() - 1) == '-');
					int start = Gpr.parseIntSafe(posStart) - 1;
					int end = Gpr.parseIntSafe(posEnd) - 1;

					if (debug) System.out.println(transcriptId + " " + chr + ":" + start + "-" + end);
					msa = new MultipleSequenceAlignment(this, transcriptId, numAligns, seqLen);
					msa.set(genome.getOrCreateChromosome(chr), start, end, strand);
				} else if (sequence.length() != seqLen) throw new RuntimeException("Error (file '" + sequenceAlignmentFile + "', line " + lif.getLineNum() + "): Expecting sequence of length " + seqLen);

				// Add sequence
				if (debug) System.out.println("\t" + sequence + "\t" + species[i]);

				// Remove ambiguous amino acids
				sequence = sequence.replace('B', '-');
				sequence = sequence.replace('Z', '-');
				sequence = sequence.replace('J', '-');
				sequence = sequence.replace('X', '-');

				// Remove rare amino acids
				sequence = sequence.replace('U', '-');
				sequence = sequence.replace('O', '-');

				// Stop codons
				sequence = sequence.replace('*', '-');

				// Set sequence
				msa.set(i, sequence, header);
			}

			if (msa != null) add(msa);

			// Empty line separator
			String emptyLine = lif.next();
			if (emptyLine != null && !emptyLine.isEmpty()) throw new RuntimeException("Error (file '" + sequenceAlignmentFile + "', line " + lif.getLineNum() + "): Expecting an empty line!");
		}

		// Sort lists
		sortTranscriptLists();
	}

	/**
	 * Does this marker match any interval where MSA are available?
	 */
	public Markers query(Marker marker) {
		return intForest.query(marker);
	}

	/**
	 * Get a random element
	 */
	public MultipleSequenceAlignment rand(Random random) {
		return msas.get(random.nextInt(msas.size()));
	}

	public String rowSequence(String trid) {
		return rowSequence(trid, null);
	}

	/**
	 * Find a row sequence
	 * @param trid : Transcript ID
	 * @param chr : Chromosome name (to check that matches the MSA). It can be null, in which case, checking is skipped.
	 */
	public String rowSequence(String trid, String chr) {
		// Find all MSA
		List<MultipleSequenceAlignment> msaList = msasByTrId.get(trid);
		if (msaList == null) return null;

		// Get all msas for this 'trid'
		ArrayList<MultipleSequenceAlignment> msasTr = new ArrayList<>();
		boolean reverse = false;
		for (MultipleSequenceAlignment msa : msaList) {
			// Different chromosome or position? Skip
			if (chr != null && !msa.getChromosomeName().equals(chr)) continue;
			msasTr.add(msa);
			reverse |= msa.isStrandMinus();
		}

		// Sort
		if (reverse) Collections.sort(msasTr, Collections.reverseOrder());
		else Collections.sort(msasTr);

		StringBuilder sb = new StringBuilder();
		for (MultipleSequenceAlignment msa : msasTr)
			sb.append(msa.getRowString(0));

		return sb.toString();
	}

	public void setSpecies(String[] species) {
		this.species = species;
	}

	public int size() {
		return msas.size();
	}

	/**
	 * Get all transcript lists sorted by strand
	 */
	void sortTranscriptLists() {
		for (List<MultipleSequenceAlignment> l : msasByTrId.values()) {
			if (l.isEmpty()) continue;
			MultipleSequenceAlignment msa = l.get(0); // First msa in the list

			// Sort by strand
			if (msa.isStrandPlus()) Collections.sort(l);
			else Collections.sort(l, Comparator.reverseOrder());
		}
	}

	public Stream<MultipleSequenceAlignment> stream() {
		return StreamSupport.stream(spliterator(), false);
	}
}
