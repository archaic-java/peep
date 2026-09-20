/** Goal-scoped diagnostic providers for the Archaic service catalog. */
module work.archaic.peep {
    requires transitive work.archaic.service.catalog;
    exports work.archaic.peep;
    provides work.archaic.service.logging.v01.GoalProvider with work.archaic.peep.Peep;
    provides work.archaic.service.logging.v01.Log with work.archaic.peep.TextLog;
}
