module work.archaic.peep.test {
    requires work.archaic.service.catalog;
    requires jdk.httpserver;
    requires java.net.http;
    exports work.archaic.peep.test;
    opens work.archaic.peep.test to work.archaic.minau;
    uses work.archaic.service.logging.v01.GoalProvider;
    uses work.archaic.service.logging.v01.Log;
    exports work.archaic.peep.test.v02;
    opens work.archaic.peep.test.v02 to work.archaic.minau;
    uses work.archaic.service.logging.v02.Diagnostics;
    uses work.archaic.service.logging.v02.Log;
}

