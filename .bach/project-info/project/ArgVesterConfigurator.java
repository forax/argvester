package project;

import com.github.sormuras.bach.Configurator;
import com.github.sormuras.bach.Project;

public class ArgVesterConfigurator implements Configurator {
  @Override
  public Project configureProject(Project project) {
    return project
        .withName("ArgVester")
        .withVersion("1.1")
        .withTargetsJava(17)
        .withAdditionalCompileJavacArguments("main", "-Xlint")
        .withExternalModules("junit", "5.8.2");
  }
}
