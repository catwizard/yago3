package extractors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javatools.administrative.Announce;
import javatools.administrative.D;
import javatools.datatypes.FinalMap;
import javatools.filehandlers.FileLines;
import javatools.parsers.Char;
import javatools.parsers.Name;
import javatools.parsers.NounGroup;
import javatools.parsers.PlingStemmer;
import javatools.util.FileUtils;
import basics.Fact;
import basics.FactCollection;
import basics.FactComponent;
import basics.FactSource;
import basics.FactWriter;
import basics.RDFS;
import basics.Theme;
import extractorUtils.FactTemplateExtractor;
import extractorUtils.TitleExtractor;

/**
 * CategoryExtractor - YAGO2s
 * 
 * Extracts facts and types from categories
 * 
 * @author Fabian
 * 
 */
public class CategoryExtractor extends Extractor {

	/** The file from which we read */
	protected File wikipedia;

	@Override
	public Set<Theme> input() {
		return new TreeSet<Theme>(Arrays.asList(PatternHardExtractor.CATEGORYPATTERNS,
				PatternHardExtractor.TITLEPATTERNS, HardExtractor.HARDWIREDFACTS, WordnetExtractor.WORDNETWORDS,
				WordnetExtractor.WORDNETCLASSES));
	}

	/** Types deduced from categories */
	public static final Theme CATEGORYTYPES = new Theme("categoryTypes");
	/** Facts deduced from categories */
	public static final Theme CATEGORYFACTS = new Theme("categoryFacts");
	/** Classes deduced from categories */
	public static final Theme CATEGORYCLASSES = new Theme("categoryClasses");

	@Override
	public Map<Theme, String> output() {
		return new FinalMap<Theme, String>(CATEGORYTYPES, "Types derived from the categories", CATEGORYFACTS,
				"Facts derived from the categories", CATEGORYCLASSES, "Classes derived from the categories");
	}

	/** Maps a category to a wordnet class */
	public static String category2class(String categoryName, Set<String> nonconceptual,
			Map<String, String> preferredMeaning) {
		// Check out whether the new category is worth being added
		NounGroup category = new NounGroup(categoryName);
		if (category.head() == null) {
			Announce.debug("Could not find type in", categoryName, "(has empty head)");
			return (null);
		}

		// If the category is an acronym, drop it
		if (Name.isAbbreviation(category.head())) {
			Announce.debug("Could not find type in", categoryName, "(is abbreviation)");
			return (null);
		}
		category = new NounGroup(categoryName.toLowerCase());

		// Only plural words are good hypernyms
		if (PlingStemmer.isSingular(category.head()) && !category.head().equals("people")) {
			Announce.debug("Could not find type in", categoryName, "(is singular)");
			return (null);
		}
		String stemmedHead = PlingStemmer.stem(category.head());

		// Exclude the bad guys
		if (nonconceptual.contains(stemmedHead)) {
			Announce.debug("Could not find type in", categoryName, "(is non-conceptual)");
			return (null);
		}

		// Try all premodifiers (reducing the length in each step) + head
		if (category.preModifier() != null) {
			String wordnet = null;
			String preModifier = category.preModifier().replace('_', ' ');

			for (int start = 0; start != -1 && start < preModifier.length() - 2; start = preModifier.indexOf(' ',
					start + 1)) {
				wordnet = preferredMeaning.get(preModifier.substring(start) + " " + stemmedHead);
				// take the longest matching sequence
				if (wordnet != null)
					return (wordnet);
			}
		}

		// Try head
		String wordnet = preferredMeaning.get(stemmedHead);
		if (wordnet != null)
			return (wordnet);
		Announce.debug("Could not find type in", categoryName, "(no wordnet match)");
		return (null);
	}

	/**
	 * Extracts type from the category name
	 * 
	 * @param classWriter
	 */
	protected void extractType(String titleEntity, String category, FactCollection facts, FactCollection categoryFacts,
			Set<String> nonconceptual, Map<String, String> preferredMeaning) throws IOException {
		String concept = category2class(category, nonconceptual, preferredMeaning);
		if (concept == null)
			return;
		facts.add(new Fact(null, titleEntity, RDFS.type, FactComponent.forWikiCategory(category)));
		categoryFacts.add(new Fact(null, FactComponent.forWikiCategory(category), RDFS.subclassOf, concept));
	}

	/** Returns the set of non-conceptual words */
	public static Set<String> nonConceptualWords(FactCollection categoryPatterns) {
		return (categoryPatterns.asStringSet("<_yagoNonConceptualWord>"));
	}

	@Override
	public void extract(Map<Theme, FactWriter> writers, Map<Theme, FactSource> input) throws Exception {

		FactCollection categoryPatternCollection = new FactCollection(input.get(PatternHardExtractor.CATEGORYPATTERNS));
		FactTemplateExtractor categoryPatterns = new FactTemplateExtractor(categoryPatternCollection,
				"<_categoryPattern>");
		Set<String> nonconceptual = nonConceptualWords(categoryPatternCollection);
		Map<String, String> preferredMeanings = WordnetExtractor.preferredMeanings(
				new FactCollection(input.get(HardExtractor.HARDWIREDFACTS)),
				new FactCollection(input.get(WordnetExtractor.WORDNETWORDS)));
		TitleExtractor titleExtractor = new TitleExtractor(input);
		FactCollection wordnetClasses = new FactCollection(input.get(WordnetExtractor.WORDNETCLASSES));
		FactCollection categoryClasses = new FactCollection();

		// Extract the information
		Announce.doing("Extracting");
		Reader in = FileUtils.getBufferedUTF8Reader(wikipedia);
		String titleEntity = null;
		FactCollection facts = new FactCollection();
		while (true) {
			switch (FileLines.findIgnoreCase(in, "<title>", "[[Category:")) {
			case -1:
				flush(titleEntity,facts, writers, categoryClasses,wordnetClasses);
				for (Fact f : categoryClasses)
					writers.get(CATEGORYCLASSES).write(f);
				Announce.done();
				in.close();
				return;
			case 0:
				flush(titleEntity,facts, writers, categoryClasses, wordnetClasses);
				titleEntity = titleExtractor.getTitleEntity(in);
				if (titleEntity != null) {
					for (String name : namesOf(titleEntity)) {
						facts.add(new Fact(titleEntity, RDFS.label, name));
					}
				}
				break;
			case 1:
				if (titleEntity == null)
					continue;
				String category = FileLines.readTo(in, "]]").toString();
				if (!category.endsWith("]]"))
					continue;
				category = category.substring(0, category.length() - 2);
				for (Fact fact : categoryPatterns.extract(category, titleEntity)) {
					if (fact != null)
						facts.add(fact);
				}
				extractType(titleEntity, category, facts, categoryClasses, nonconceptual, preferredMeanings);
			}
		}
	}

	/** Writes the facts */
	public static void flush(String entity, FactCollection facts, Map<Theme, FactWriter> writers,
			FactCollection categoryClasses, FactCollection wordnetClasses) throws IOException {
		if(entity==null) return;
		String yagoBranch = yagoBranch(entity, facts, categoryClasses, wordnetClasses);
		Announce.debug("Branch of", entity, "is", yagoBranch);
		if (yagoBranch == null)
			return;
		for (Fact fact : facts) {
			switch (fact.getRelation()) {
			case RDFS.type:
				String branch=yagoBranch(fact.getArg(2), categoryClasses, wordnetClasses);
				if (branch==null || !branch.equals(yagoBranch)) {
					Announce.debug("Wrong branch:", fact.getArg(2),branch);
				} else {
					writers.get(CATEGORYTYPES).write(fact);
				}
				break;
			case RDFS.subclassOf:
				writers.get(CATEGORYCLASSES).write(fact);
				break;
			default:
				writers.get(CATEGORYFACTS).write(fact);
			}
		}
		facts.clear();
	}

	/** Returns the YAGO branch for a category class */
	public static String yagoBranch(String arg, FactCollection categoryClasses, FactCollection wordnetClasses) {
		String yagoBranch = SimpleTaxonomyExtractor.yagoBranch(arg, wordnetClasses);
		if (yagoBranch != null)
			return (yagoBranch);
		for (String sup : categoryClasses.getArg2s(arg, RDFS.subclassOf)) {
			yagoBranch = SimpleTaxonomyExtractor.yagoBranch(sup, wordnetClasses);
			if (yagoBranch != null)
				return (yagoBranch);
		}
		return null;
	}

	/** Returns the YAGO branch for a an entity */
	public static String yagoBranch(String entity, FactCollection facts, FactCollection categoryClasses,
			FactCollection wordnetClasses) {
		Map<String, Integer> branches = new TreeMap<>();
		for (Fact type : facts.get(entity, RDFS.type)) {
			String yagoBranch = yagoBranch(type.getArg(2), categoryClasses, wordnetClasses);
			if (yagoBranch != null)
				D.addKeyValue(branches, yagoBranch, 1);
		}
		String yagoBranch = null;
		for (String b : branches.keySet()) {
			if (yagoBranch == null || branches.get(b) > branches.get(yagoBranch))
				yagoBranch = b;
		}
		return (yagoBranch);
	}

	/** returns the (trivial) names of an entity */
	public static Set<String> namesOf(String titleEntity) {
		Set<String> result = new TreeSet<>();
		if (titleEntity.startsWith("<"))
			titleEntity = titleEntity.substring(1);
		if (titleEntity.endsWith(">"))
			titleEntity = Char.cutLast(titleEntity);
		String name = Char.decode(titleEntity.replace('_', ' '));
		result.add(FactComponent.forString(name));
		result.add(FactComponent.forString(Char.normalize(name)));
		if (name.contains(" (")) {
			result.add(FactComponent.forString(name.substring(0, name.indexOf(" (")).trim()));
		}
		if (name.contains(",")) {
			result.add(FactComponent.forString(name.substring(0, name.indexOf(",")).trim()));
		}
		return (result);
	}

	/** Constructor from source file */
	public CategoryExtractor(File wikipedia) {
		this.wikipedia = wikipedia;
	}

	public static void main(String[] args) throws Exception {
		Announce.setLevel(Announce.Level.DEBUG);
		//new PatternHardExtractor(new File("./data")).extract(new File("c:/fabian/data/yago2s"),
		//		"Test on 1 wikipedia article");
		new CategoryExtractor(new File("./testCases/wikitest.xml")).extract(new File("c:/fabian/data/yago2s"),
				"Test on 1 wikipedia article");
	}
}
