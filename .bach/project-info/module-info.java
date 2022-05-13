module project {
  requires com.github.sormuras.bach;

  provides com.github.sormuras.bach.Configurator with
      project.ArgVesterConfigurator;
}
