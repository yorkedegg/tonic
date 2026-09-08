.SUFFIXES:

ifeq ($(strip $(DEVKITARM)),)
$(error "Please set DEVKITARM in your environment. export DEVKITARM=<path to>devkitARM")
endif

TOPDIR 		?= 	$(CURDIR)
include $(DEVKITARM)/3ds_rules

# NOTE: TARGET is pinned rather than derived from the folder name, so the release
# artifact is always CTRComposer-BlankTemplate.3gx no matter what you clone into.
# Rename it (and PLGINFO / the Title: in the .plgInfo) when you fork the template.
TARGET		:=	tonic
PLGINFO		:= 	tonic.plgInfo
BUILD		:= 	build
INCLUDES	:= 	Includes
LIBDIRS		:= 	$(CTRULIB)
SOURCES 	:= 	Sources

#---------------------------------------------------------------------------------
# options for code generation
#---------------------------------------------------------------------------------
ARCH		:=	-march=armv6k -mlittle-endian -mtune=mpcore -mfloat-abi=hard

# -Wall -Wextra is worth keeping ON. This project shipped for a while without it; when it was
# finally switched on the tree was clean apart from two deliberate cases (the unused `arg` that
# svcCreateThread's signature forces, and `void main` in a plugin with no crt0). Silence here is
# information - if a warning appears, read it.
# -Wno-main: this is a plugin, not a program. Luma calls main() through the 3gx bootstrap, so it
# takes no argc/argv and returns nothing - the standard signature would be a lie.
CFLAGS		:=	-Os -mword-relocations -Wall -Wextra -Wno-main \
				-fomit-frame-pointer -ffunction-sections -fno-strict-aliasing \
				$(ARCH)

CFLAGS		+=	$(INCLUDE) -D__3DS__

CXXFLAGS	:= $(CFLAGS) -fno-rtti -fno-exceptions -std=gnu++11

ASFLAGS		:= $(ARCH)
LDFLAGS		:= -T $(TOPDIR)/3ds.ld $(ARCH) -Os -Wl,-Map,$(notdir $*.map),--gc-sections

LIBS		:= -lctru

#---------------------------------------------------------------------------------
# no real need to edit anything past this point unless you need to add additional
# rules for different file extensions
#---------------------------------------------------------------------------------
ifneq ($(BUILD),$(notdir $(CURDIR)))
#---------------------------------------------------------------------------------

export OUTPUT	:=	$(CURDIR)/$(TARGET)
export TOPDIR	:=	$(CURDIR)
export VPATH	:=	$(foreach dir,$(SOURCES),$(CURDIR)/$(dir)) \
					$(foreach dir,$(DATA),$(CURDIR)/$(dir))

export DEPSDIR	:=	$(CURDIR)/$(BUILD)

CFILES			:=	$(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.c)))
CPPFILES		:=	$(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.cpp)))
SFILES			:=	$(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.s)))

export LD 		:= 	$(CXX)
export OFILES	:=	$(CPPFILES:.cpp=.o) $(CFILES:.c=.o) $(SFILES:.s=.o)
export INCLUDE	:=	$(foreach dir,$(INCLUDES),-I $(CURDIR)/$(dir) ) \
					$(foreach dir,$(LIBDIRS),-I $(dir)/include) \
					-I $(CURDIR)/$(BUILD)

export LIBPATHS	:=	$(foreach dir,$(LIBDIRS),-L $(dir)/lib)

.PHONY: $(BUILD) clean all

#---------------------------------------------------------------------------------
all: $(BUILD)

$(BUILD):
	@[ -d $@ ] || mkdir -p $@
	@$(MAKE) --no-print-directory -C $(BUILD) -f $(CURDIR)/Makefile

#---------------------------------------------------------------------------------
clean:
	@echo clean ...
	@-rm -fr $(BUILD) $(OUTPUT).3gx $(OUTPUT).elf

re: clean all

#---------------------------------------------------------------------------------

else

DEPENDS	:=	$(OFILES:.o=.d)

#---------------------------------------------------------------------------------
# main targets
#---------------------------------------------------------------------------------
# The .3gx rule must come FIRST: it is the inner make's default goal.
$(OUTPUT).3gx : $(OUTPUT).elf $(TOPDIR)/$(PLGINFO)
	@echo creating $(notdir $@)
	@cp $(OUTPUT).elf $(OUTPUT).strip.elf
	@arm-none-eabi-strip --strip-debug $(OUTPUT).strip.elf
	@3gxtool -s $(OUTPUT).strip.elf $(TOPDIR)/$(PLGINFO) $@

# The elf must depend on the objects, or edits compile but never relink (crash 5 shipped stale code).
$(OUTPUT).elf : $(OFILES)

#---------------------------------------------------------------------------------
# you need a rule like this for each extension you use as binary data
#---------------------------------------------------------------------------------
%.bin.o	:	%.bin
#---------------------------------------------------------------------------------
	@echo $(notdir $<)
	@$(bin2o)

#---------------------------------------------------------------------------------
%.3gx: %.elf
	@echo creating $(notdir $@)
	@3gxtool -s $(word 1, $^) $(TOPDIR)/$(PLGINFO) $@

# Without this, make treats the .elf as a disposable intermediate of the .elf->.3gx chain and
# deletes it right after linking. Keeping it around lets Tools/fingerprint.sh inspect symbols
# (see PLANO-REFATORACAO.md, Etapa 0) instead of every refactor step needing a fresh objdump.
.PRECIOUS: %.elf

-include $(DEPENDS)

#---------------------------------------------------------------------------------------
endif
