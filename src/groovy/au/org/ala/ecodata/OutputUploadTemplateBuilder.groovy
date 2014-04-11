package au.org.ala.ecodata
import au.org.ala.ecodata.metadata.OutputMetadata
import au.org.ala.ecodata.metadata.OutputModelProcessor
import au.org.ala.ecodata.reporting.XlsExporter
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.ss.util.CellReference
import pl.touk.excel.export.multisheet.AdditionalSheet

class OutputUploadTemplateBuilder extends XlsExporter {

    def model
    def outputName

    public OutputUploadTemplateBuilder(outputName, model) {
        super(outputName)
        this.outputName = outputName
        this.model = model.findAll{!it.computed}
    }

    public void build() {

        def headers = model.collect {
            def label = it.label ?: it.name
            if (it.dataType == 'species') {
                label += ' (Scientific Name Only)'
            }
            label
        }
        AdditionalSheet outputSheet = addSheet(outputName, headers)

        new ValidationProcessor(getWorkbook(), outputSheet.sheet, model).process()
        finalise()
    }

    def finalise() {
        sizeColumns()
    }

}


class ValidationProcessor extends OutputModelProcessor {

    private Workbook workbook
    private Sheet sheet
    def model

    public ValidationProcessor(workbook, sheet, model) {
        this.workbook = workbook
        this.sheet = sheet
        this.model = model
    }

    public void process() {

        // Create a worksheet to store validation lists in.
        def validationSheetName = OutputUploadTemplateBuilder.sheetName("Validation - "+sheet.getSheetName())
        Sheet validationSheet = workbook.createSheet(validationSheetName)
        workbook.setSheetHidden(workbook.getSheetIndex(validationSheet), true)

        ExcelValidationContext context = new ExcelValidationContext([currentSheet:sheet, validationSheet:validationSheet])
        ValidationHandler validationHandler = new ValidationHandler()
        model.eachWithIndex{node, i ->
            context.currentColumn = i
            processNode(validationHandler, node, context)
        }
    }


}

class ExcelValidationContext implements OutputModelProcessor.ProcessingContext {
    Sheet currentSheet
    Sheet validationSheet
    int currentColumn

}


class ValidationHandler implements OutputModelProcessor.Processor<ExcelValidationContext> {


    def addValidation(node, context, constraint = null) {

        DataValidationHelper dvHelper = context.currentSheet.getDataValidationHelper();
        OutputMetadata.ValidationRules rules = new OutputMetadata.ValidationRules(node)

        DataValidation dataValidation = dvHelper.createValidation(constraint, columnRange(context.currentColumn))
        if (rules.isMandatory()) {
            dataValidation.setEmptyCellAllowed(false)
        }
        dataValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        dataValidation.setShowErrorBox(true);

        context.currentSheet.addValidationData(dataValidation);
    }

    @Override
    def number(Object node, ExcelValidationContext context) {
        DataValidationHelper dvHelper = context.currentSheet.getDataValidationHelper();
        OutputMetadata.ValidationRules rules = new OutputMetadata.ValidationRules(node)

        def operator = rules.max() ? DataValidationConstraint.OperatorType.BETWEEN : DataValidationConstraint.OperatorType.GREATER_OR_EQUAL

        DataValidationConstraint dvConstraint =
                dvHelper.createNumericConstraint(DataValidationConstraint.ValidationType.DECIMAL,
                operator, rules.min().toString(), rules.max()?:"")

        addValidation(node, context, dvConstraint)
    }

    @Override
    def integer(Object node, ExcelValidationContext context) {
        number(node, context)
    }

    @Override
    def text(Object node, ExcelValidationContext context) {
        if (node.constraints) {

            node.constraints.eachWithIndex { value, i ->
                Row row = context.validationSheet.getRow(i)
                if (!row) {
                    row = context.validationSheet.createRow(i)
                }
                Cell cell = row.createCell(context.currentColumn)
                cell.setCellValue(value)
            }
            def colString = CellReference.convertNumToColString(context.currentColumn)
            def rangeFormula = "'${context.validationSheet.getSheetName()}'!\$${colString}\$1:\$${colString}\$${node.constraints.length()}"

            DataValidationHelper dvHelper = context.currentSheet.getDataValidationHelper();
            DataValidationConstraint dvConstraint =
                    dvHelper.createFormulaListConstraint(rangeFormula)


            addValidation(node, context, dvConstraint)

        }
    }

    @Override
    def date(Object node, ExcelValidationContext context) {

    }

    @Override
    def image(Object node, ExcelValidationContext context) {

    }

    @Override
    def embeddedImages(Object node, ExcelValidationContext context) {

    }

    @Override
    def species(Object node, ExcelValidationContext context) {

    }

    @Override
    def stringList(Object node, ExcelValidationContext context) {

    }

    def columnRange(int col) {
        final int MAX_ROWS = 1000
        CellRangeAddressList range = new CellRangeAddressList(1, MAX_ROWS, col, col)
        return range
    }

}