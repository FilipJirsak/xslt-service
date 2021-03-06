package org.jirsak.service.xslt.transformer;

import io.micronaut.http.MediaType;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.serialize.SerializationProperties;

import javax.inject.Singleton;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Filip Jirsák
 */
@Singleton
public class TransformerService {
  private static final String NS_JINOTAJ = "https://jinotaj.org/2020/XSLT-service";
  private static final String NS_JINOTAJ_CLARK = '{' + NS_JINOTAJ + '}';
  private final Path root;
  private final FopService fopService;
  private final SaxonService saxonService;
  private final ConcurrentMap<Path, TransformerFactory> transformerCache;

  public TransformerService(TransformerConfig configuration, SaxonService saxonService, FopService fopService) {
    this.saxonService = saxonService;
    this.fopService = fopService;
    this.root = configuration.getRoot().normalize().toAbsolutePath();
    this.transformerCache = new ConcurrentHashMap<>();
  }

  public Transformer getTransformer(String uri) throws IOException {
    Path path = computePath(uri);
//		TransformerFactory transformerFactory = transformerCache.computeIfAbsent(path, this::createTransformerFactory);
    TransformerFactory transformerFactory = this.createTransformerFactory(path);
    return transformerFactory.create();
  }

  private Path computePath(String uri) throws IOException {
    Path path = root.resolve(uri).normalize();
    if (!path.startsWith(root)) {
      throw new FileNotFoundException(uri);
    }
    return path;
  }

  private TransformerFactory createTransformerFactory(Path path) {
    try {
      Path parent = path.getParent();
      String name = path.getFileName().toString();
      Path pathWithExtension;
      Iterator<String> extensionIterator = Arrays.asList("xslt", "xsl").iterator();
      do {
        pathWithExtension = pathWithExtension(parent, name, extensionIterator.next());
      } while (Files.notExists(pathWithExtension) && extensionIterator.hasNext());
      if (Files.notExists(pathWithExtension)) {
        throw new FileNotFoundException();
      }
      Path xsltPath = pathWithExtension;

      TransformerFactory transformerFactory = new TransformerFactory();

      XsltExecutable xsltExecutable = saxonService.createXsltExecutable(xsltPath);
      SerializationProperties serializationProperties = xsltExecutable.getUnderlyingCompiledStylesheet().getPrimarySerializationProperties();
      if (MediaType.APPLICATION_PDF.equals(serializationProperties.getProperty("media-type"))) {
        transformerFactory.setDestinationFactory(outputStream -> fopService.createFopDestination(outputStream, xsltPath));
      } else {
        transformerFactory.setDestinationFactory(saxonService::createSerializer);
      }

      String previousTemplates = serializationProperties.getProperty(NS_JINOTAJ_CLARK + "previous-templates");
      appendTemplates(parent, transformerFactory, previousTemplates);

      transformerFactory.appendXslt(xsltExecutable);

      String nextTemplates = serializationProperties.getProperty(NS_JINOTAJ_CLARK + "next-templates");
      appendTemplates(parent, transformerFactory, nextTemplates);

      String fileName = serializationProperties.getProperty(NS_JINOTAJ_CLARK + "file-name");
      transformerFactory.setFileName(fileName);
      return transformerFactory;
    } catch (SaxonApiException | FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private void appendTemplates(Path parent, TransformerFactory transformerFactory, String templates) throws SaxonApiException {
    if (templates != null) {
      for (String file : templates.split("\\s*,\\s*")) {
        Path path = parent.resolve(file);
        XsltExecutable prevXsltExecutable = saxonService.createXsltExecutable(path);
        transformerFactory.appendXslt(prevXsltExecutable);
      }
    }
  }

  private Path pathWithExtension(Path parent, String name, String extension) {
    return parent.resolve(name + '.' + extension);
  }
}
