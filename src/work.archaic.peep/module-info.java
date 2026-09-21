/** Goal-scoped diagnostic providers for the Archaic service catalog. */
module work.archaic.peep {
    requires work.archaic.service.catalog;
    provides work.archaic.service.logging.v01.GoalProvider with work.archaic.peep.Peep;
    provides work.archaic.service.logging.v01.Log with work.archaic.peep.TextLog;
}
