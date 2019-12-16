//jsMain: 'https://dl.dropbox.com/s/0jzodm9z0l3n8q2/groundCover.js?dl=0'
var Output_BioConditionConsolidated_groundCoverRow = function (data, dataModel, context, config) {
    var self = this;
    ecodata.forms.NestedModel.apply(self, [data, dataModel, context, config]);
    context = _.extend(context, {parent:self});
    self.groundCoverType = ko.observable();

    self.plot1 = ko.observable().extend({numericString:2});

    self.plot2 = ko.observable().extend({numericString:2});

    self.plot3 = ko.observable().extend({numericString:2});

    self.plot4 = ko.observable().extend({numericString:2});

    self.plot5 = ko.observable().extend({numericString:2});
    self.plotsMean = ko.computed(function () {
        if (isNaN(Number(self.plot1())) || isNaN(Number(self.plot2())) || isNaN(Number(self.plot3())) || isNaN(Number(self.plot4())) || isNaN(Number(self.plot5()))) { return 0; }
        return Number(self.plot1()) + Number(self.plot2()) + Number(self.plot3()) + Number(self.plot4()) + Number(self.plot5());
    });

    self.groundCoverScore = ko.computed(function () {
        // Get the data from table 13 and use the mean and calculate the score.
        var plotMean = self.plotsMean();
        var assessmentPercentage = 0;
        var benchmarkMarkGroundCoverNativeGrassCover = self.$parent.data.benchmarkMarkGroundCoverNativeGrassCover;

        if(benchmarkMarkGroundCoverNativeGrassCover != 'na' && !isNaN(plotMean) && !isNaN(benchmarkMarkGroundCoverNativeGrassCover) && benchmarkMarkGroundCoverNativeGrassCover > 0) {
            assessmentPercentage = parseInt(plotMean)/parseInt(benchmarkMarkGroundCoverNativeGrassCover) * 100;
        }

        //TODO: Why no benchmarkMarkGroundCoverOrganicLitterCover used in the form?
        var score = 0;
        var table;
        // Get the table value
        $.grep(!$.isEmptyObject(self.$parent.bioConditionAssessmentTableReference) ? self.$parent.bioConditionAssessmentTableReference.value : [], function (row) {
            if (row.key == 'table_13') {
                table = row;
            }
        });

        var score = 0;
        if(table && table.value && table.value.length == 4) {
            if (table.value[0].name == '<10% of benchmark native perennial (or preferred and intermediate) grass cover' && assessmentPercentage < 10) {
                score = table.value[0].value;
            } else if (table.value[1].name == '≥10 to 50% of benchmark native perennial (or preferred and intermediate) grass cover' && assessmentPercentage >= 10 && assessmentPercentage < 50) {
                score = table.value[1].value;
            } else if (table.value[2].name == '≥50 – 90% of benchmark native perennial (or preferred and intermediate) grass cover' && assessmentPercentage >= 50 && assessmentPercentage < 90) {
                score = table.value[2].value;
            } else if (table.value[3].name == '≥90% of benchmark native perennial (or preferred and intermediate) grass cover' && assessmentPercentage >= 90) {
                score = table.value[3].value;
            }
        }

        return score;

    });

    self.loadData = function(data) {
        self['groundCoverType'](ecodata.forms.orDefault(data['groundCoverType'], undefined));
        self['plot1'](ecodata.forms.orDefault(data['plot1'], 0));
        self['plot2'](ecodata.forms.orDefault(data['plot2'], 0));
        self['plot3'](ecodata.forms.orDefault(data['plot3'], 0));
        self['plot4'](ecodata.forms.orDefault(data['plot4'], 0));
        self['plot5'](ecodata.forms.orDefault(data['plot5'], 0));
    };
    self.loadData(data || {});
};
var context = _.extend({}, context, {parent:self, listName:'groundCover'});
self.data.groundCover = ko.observableArray().extend({list:{metadata:self.dataModel,
    constructorFunction:Output_BioConditionConsolidated_groundCoverRow,
    context:context,
    userAddedRows:false,
    config:config
}});
self.data.groundCover.loadDefaults = function() {
    self.data.groundCover.addRow({"groundCoverType":"% Native Perennial ('decreaser') grass cover*"});
    self.data.groundCover.addRow({"groundCoverType":"% Native other grass cover (if relevant)*"});
    self.data.groundCover.addRow({"groundCoverType":"% Native forbs and other species (non-grass)"});
    self.data.groundCover.addRow({"groundCoverType":"% Native shrubs (< 1m height)"});
    self.data.groundCover.addRow({"groundCoverType":"% Non-native grass"});
    self.data.groundCover.addRow({"groundCoverType":"% Non-native forbs and shrubs"});
    self.data.groundCover.addRow({"groundCoverType":"% Litter*"});
    self.data.groundCover.addRow({"groundCoverType":"% Rock"});
    self.data.groundCover.addRow({"groundCoverType":"% Bare ground"});
    self.data.groundCover.addRow({"groundCoverType":"% Cryptograms"});
};