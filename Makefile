project = accelShell

TARGET ?= SimMem

# Toolchains and tools
MILL = ./../playground/mill

-include ./../playground/Makefile.include

# Targets
rtl: check-firtool ## Generates Verilog code from Chisel sources (output to ./generated_sv_dir)
	$(MILL) $(project).runMain $(project).accelShellMain $(TARGET)

check: test
.PHONY: test
test:## Run Chisel tests
#	$(MILL) $(project).test.testOnly 
#	@echo "If using WriteVcdAnnotation in your tests, the VCD files are generated in ./test_run_dir/testname directories."

.PHONY: verilate
verilate: check-firtool check-verilate ## Generate Verilator simulation executable for Module level testing
#	$(MILL) $(project).runMain $(project).TestMain $(TARGET)
