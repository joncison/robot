package org.obolibrary.robot;

import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.sparql.core.DatasetGraph;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.commons.io.FileUtils;
import org.obolibrary.robot.checks.Report;
import org.obolibrary.robot.checks.Violation;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Report issues with an ontology.
 *
 * <p>Currently this is minimal but we imagine later creating an extensive 'report card' for an
 * ontology, describing ways to make the ontology conform to OBO conventions
 *
 * <p>TODO: decide on report structure. Perhaps JSON-LD? Create vocabulary for report violations?
 */
public class ReportOperation {

  /** Directory for queries. */
  private static final String queryDir = "queries";

  /** Logger. */
  private static final Logger logger = LoggerFactory.getLogger(ReportOperation.class);

  /** Namespace for general input error messages. */
  private static final String NS = "report#";

  /** Error message when user provides a rule level other than INFO, WARN, or ERROR. */
  private static final String reportLevelError =
      NS + "REPORT LEVEL ERROR '%s' is not a valid reporting level.";

  /** Reporting levels. */
  private static final String INFO = "INFO";

  private static final String WARN = "WARN";
  private static final String ERROR = "ERROR";

  /**
   * Given an ontology, a profile path (or null), and an output path (or null), report on the
   * ontology using the rules within the profile and write results to the output path. If profile is
   * null, use the default profile in resources. If the output path is null, write results to
   * console.
   *
   * @param ontology OWLOntology to report on
   * @param outputPath string path to write report file to, or null
   * @param profilePath user profile file path to use, or null
   * @throws Exception on any error
   */
  public static void report(OWLOntology ontology, String profilePath, String outputPath)
      throws Exception {
    // The profile is a map of rule name and reporting level
    Map<String, String> profile = getProfile(profilePath);
    // The queries is a map of rule name and query string
    Map<String, String> queries = getQueryStrings(profile.keySet());
    Report report = createReport(ontology, profile, queries);
    // System.out.println(report.getIRIs());
    // String result = report.toYaml();
    String result = report.toTSV();
    if (outputPath != null) {
      // If output is provided, write to that file
      try (FileWriter fw = new FileWriter(outputPath);
          BufferedWriter bw = new BufferedWriter(fw)) {
        logger.debug("Writing report to: " + outputPath);
        bw.write(result);
      }
    } else {
      // Otherwise output to terminal
      System.out.println(result);
    }
  }

  /**
   * Given an ontology and a set of queries, create a report containing all violations. Violations
   * are added based on the query results. If there are no violations, the Report will pass and no
   * Report object will be returned.
   *
   * @param ontology OWLOntology to report on
   * @param queries set of CheckerQueries
   * @return Report (on violations) or null (on no violations)
   * @throws OWLOntologyStorageException on issue loading ontology as DatasetGraph
   * @throws Exception
   */
  private static Report createReport(
      OWLOntology ontology, Map<String, String> profile, Map<String, String> queries)
      throws OWLOntologyStorageException {
    Report report = new Report();
    DatasetGraph dsg = QueryOperation.loadOntology(ontology);
    for (String queryName : queries.keySet()) {
      report.addViolations(
          queryName, profile.get(queryName), getViolations(dsg, queries.get(queryName)));
    }
    Integer violationCount = report.getTotalViolations();
    if (violationCount != 0) {
      System.out.println("Violations: " + violationCount);
      System.out.println("-----------------");
      System.out.println("INFO:       " + report.getTotalViolations(INFO));
      System.out.println("WARN:       " + report.getTotalViolations(WARN));
      System.out.println("ERROR:      " + report.getTotalViolations(ERROR));
    } else {
      System.out.println("No violations found.");
    }
    return report;
  }

  /**
   * Given a set of rules (either as the default rule names or URL to a file), return a map of the
   * rule names and the corresponding query strings.
   *
   * @param rules set of rules to get queries for
   * @return map of rule name and query string
   * @throws IOException on any issue reading the query file
   * @throws URISyntaxException on issue converting URL to URI
   */
  private static Map<String, String> getQueryStrings(Set<String> rules)
      throws IOException, URISyntaxException {
    Set<String> defaultRules = new HashSet<>();
    Set<String> userRules = new HashSet<>();
    Map<String, String> queries = new HashMap<>();
    for (String rule : rules) {
      if (rule.startsWith("file://")) {
        userRules.add(rule);
      } else {
        defaultRules.add(rule);
      }
    }
    queries.putAll(getDefaultQueryStrings(defaultRules));
    queries.putAll(getUserQueryStrings(userRules));
    return queries;
  }

  /**
   * Given a set of user-provided query paths for a set of rules, return a map of the rule names and
   * the file (query) contents.
   *
   * @param rules set of file paths to user query files
   * @return map of rule name and query string
   * @throws URISyntaxException on issue converting file path URL to URI
   * @throws IOException on any issue reading the file
   */
  private static Map<String, String> getUserQueryStrings(Set<String> rules)
      throws URISyntaxException, IOException {
    Map<String, String> queries = new HashMap<>();
    for (String rule : rules) {
      File file = new File(new URL(rule).toURI());
      queries.put(rule, FileUtils.readFileToString(file));
    }
    return queries;
  }

  /**
   * Given a set of default rules, return a map of the rule names and query strings. This is a
   * "brute-force" method to retrieve default query file contents from packaged jar.
   *
   * @param rules subset of the default rules to include
   * @return map of rule name and query string
   * @throws URISyntaxException on issue converting path to URI
   * @throws IOException on any issue with accessing files or file contents
   */
  private static Map<String, String> getDefaultQueryStrings(Set<String> rules)
      throws IOException, URISyntaxException {
    URL dirURL = ReportOperation.class.getClassLoader().getResource(queryDir);
    Map<String, String> queries = new HashMap<>();
    // Handle simple file path, probably accessed during testing
    if (dirURL != null && dirURL.getProtocol().equals("file")) {
      String[] queryFilePaths = new File(dirURL.toURI()).list();
      if (queryFilePaths.length == 0) {
        throw new IOException("Cannot access report query files.");
      }
      for (String qPath : queryFilePaths) {
        String ruleName = qPath.substring(qPath.lastIndexOf("/")).split(".")[0];
        // Only add it to the queries if the rule set contains that rule
        // If rules == null, include all rules
        if (rules == null || rules.contains(ruleName)) {
          queries.put(ruleName, FileUtils.readFileToString(new File(qPath)));
        }
      }
      return queries;
    }
    // Handle inside jar file
    // This will be the case any time someone runs ROBOT via CLI
    if (dirURL == null) {
      String cls = ReportOperation.class.getName().replace(".", "/") + ".class";
      dirURL = ReportOperation.class.getClassLoader().getResource(cls);
    }
    if (dirURL.getProtocol().equals("jar")) {
      String jarPath = dirURL.getPath().substring(5, dirURL.getPath().indexOf("!"));
      // Get all entries in jar
      Enumeration<JarEntry> entries = null;
      try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))) {
        entries = jar.entries();
        if (!entries.hasMoreElements()) {
          throw new IOException("Cannot access entries in JAR.");
        }
        // Track rules that have successfully been retrieved
        while (entries.hasMoreElements()) {
          JarEntry resource = entries.nextElement();
          String resourceName = resource.getName();
          if (resourceName.startsWith(queryDir) && !resourceName.endsWith("/")) {
            // Get just the rule name
            String ruleName =
                resourceName.substring(
                    resourceName.lastIndexOf("/") + 1, resourceName.indexOf(".rq"));
            // Only add it to the queries if the rule set contains that rule
            // If rules == null, include all rules
            if (rules == null || rules.contains(ruleName)) {
              InputStream is = jar.getInputStream(resource);
              StringBuilder sb = new StringBuilder();
              try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String chr;
                while ((chr = br.readLine()) != null) {
                  sb.append(chr);
                }
              }
              queries.put(ruleName, sb.toString());
            }
          }
        }
      }
      return queries;
    }
    // If nothing has been returned, it's an exception
    throw new IOException("Cannot access report query files.");
  }

  /**
   * Given the path to a profile file (or null), return the rules and their levels (info, warn, or
   * error). If profile == null, return the default profile in the resources.
   *
   * @param path path to profile, or null
   * @return map of rule name and its reporting level
   * @throws IOException on any issue reading the profile file
   * @throws URISyntaxException on issue converting URL to URI
   */
  private static Map<String, String> getProfile(String path)
      throws IOException, URISyntaxException {
    Map<String, String> profile = new HashMap<>();
    InputStream is;
    // If the file was not provided, get the default
    if (path == null) {
      is = ReportOperation.class.getResourceAsStream("/report_profile.txt");
    } else {
      is = new FileInputStream(new File(path));
    }
    try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
      String line;
      while ((line = br.readLine()) != null) {
        String[] split = line.split("-");
        String level = split[0].toUpperCase().trim();
        if (!INFO.equals(level) && !WARN.equals(level) && !ERROR.equals(level)) {
          throw new IllegalArgumentException(String.format(reportLevelError, split[0].trim()));
        }
        String rule = split[1].trim();
        profile.put(rule, level);
      }
    }
    return profile;
  }

  /**
   * Given a QuerySolution and a String var to retrieve, return the value of the the var. If the var
   * is not in the solution, return null.
   *
   * @param qs QuerySolution
   * @param var String variable to retrieve from result
   * @return string or null
   */
  private static String getQueryResultOrNull(QuerySolution qs, String var) {
    try {
      return qs.get(var).toString();
    } catch (NullPointerException e) {
      return null;
    }
  }

  /**
   * Given an ontology as a DatasetGraph and a query as a CheckerQuery, return the violations found
   * by that query.
   *
   * @param dsg the ontology
   * @param query the query
   * @return List of Violations
   */
  private static List<Violation> getViolations(DatasetGraph dsg, String query) {
    ResultSet violationSet = QueryOperation.execQuery(dsg, query);

    Map<String, Violation> violations = new HashMap<>();
    Violation violation = null;

    while (violationSet.hasNext()) {
      QuerySolution qs = violationSet.next();
      // entity should never be null
      String entity = getQueryResultOrNull(qs, "entity");
      // skip RDFS and OWL terms
      // TODO: should we ignore oboInOwl, FOAF, and DC as well?
      if (entity.contains("/rdf-schema#") || entity.contains("/owl#")) {
        continue;
      }
      // find out if a this Violation already exists for this entity
      violation = violations.get(entity);
      // if the entity hasn't been added, create a new Violation
      if (violation == null) {
        violation = new Violation(entity);
      }
      // try and get a property and value from the query
      String property = getQueryResultOrNull(qs, "property");
      String value = getQueryResultOrNull(qs, "value");
      // add details to Violation
      if (property != null) {
        violation.addStatement(property, value);
      }
      violations.put(entity, violation);
    }
    return new ArrayList<>(violations.values());
  }
}
